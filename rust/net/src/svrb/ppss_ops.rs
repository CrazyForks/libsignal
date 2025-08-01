//
// Copyright 2024 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//
//! High level data operations on instances of `PpssSetup`
//!
//! These functions are useful if we ever want to perform multiple operations
//! on the same set of open connections, as opposed to having to connect for
//! each individual operation, as implied by `SvrBClient` trait.
use std::collections::VecDeque;

use futures_util::future::join_all;
use futures_util::TryFutureExt as _;
use libsignal_net_infra::ws::NextOrClose;
use libsignal_net_infra::ws2::attested::AttestedConnectionError;
pub(crate) use libsignal_svrb::{Backup4, Secret};
use libsignal_svrb::{Query4, Remove4, Restore1};
use rand::rngs::OsRng;
use rand::TryRngCore;

use super::Error;
use crate::enclave::{
    ArrayIsh, ConnectionLabel, IntoConnectionResults, LabeledConnection, PpssSetup,
};

pub fn do_prepare<Env: PpssSetup>(password: &[u8]) -> Backup4 {
    let server_ids = Env::server_ids();
    let mut rng = OsRng.unwrap_err();
    Backup4::new(
        server_ids.as_ref(),
        password,
        std::num::NonZero::new(255u32).unwrap(), // tries
        &mut rng,
    )
}

pub async fn do_backup<Env: PpssSetup>(
    connect_results: Env::ConnectionResults,
    backup: &Backup4,
) -> Result<(), Error> {
    let ConnectionContext {
        mut connections,
        errors,
    } = ConnectionContext::new(connect_results);
    if let Some(err) = errors.into_iter().next() {
        return Err(err);
    }
    let futures = connections
        .iter_mut()
        .zip(&backup.requests)
        .map(|(connection, request)| run_attested_interaction(connection, request));
    let results = join_all(futures)
        .await
        .into_iter()
        .collect::<Result<Vec<_>, _>>();
    collect_responses(results?)?;
    Ok(())
}

pub async fn do_restore<Env: PpssSetup>(
    connect_results: impl IntoConnectionResults,
    password: &[u8],
) -> Result<Secret, Error> {
    let mut rng = OsRng.unwrap_err();
    let ConnectionContext {
        mut connections,
        errors,
    } = ConnectionContext::new(connect_results);
    if let Some(err) = errors.into_iter().next() {
        return Err(err);
    }

    let server_ids = Env::server_ids();
    let restore1 = Restore1::new(server_ids.as_ref(), password, &mut rng);
    let responses1 = {
        let futures = connections
            .iter_mut()
            .zip(&restore1.requests)
            .map(|(connection, request)| run_attested_interaction(connection, request));
        let results = join_all(futures)
            .await
            .into_iter()
            .collect::<Result<Vec<_>, _>>();
        collect_responses(results?)?
    };

    let handshake_hashes = connections
        .iter()
        .map(|c| c.0.handshake_hash())
        .collect::<Vec<_>>();
    let restore2 = restore1.restore2(&responses1, &handshake_hashes, &mut rng)?;
    let responses2 = {
        let futures = connections
            .iter_mut()
            .zip(&restore2.requests)
            .map(|(connection, request)| run_attested_interaction(connection, request));
        let results = join_all(futures)
            .await
            .into_iter()
            .collect::<Result<Vec<_>, _>>();
        collect_responses(results?)?
    };
    let output = restore2.restore(&responses2)?;

    Ok(output)
}

pub async fn do_remove(connect_results: impl IntoConnectionResults) -> Result<(), Error> {
    let ConnectionContext {
        mut connections,
        errors,
    } = ConnectionContext::new(connect_results);
    for err in errors {
        // For the remove operation we ignore connection failures
        // and proceed to work with the successful connections.
        log::debug!("Connection failure '{:?}' will be ignored.", &err);
    }

    let futures = connections
        .iter_mut()
        .zip(Remove4::requests())
        .map(|(connection, request)| run_attested_interaction(connection, request));
    let results = join_all(futures)
        .await
        .into_iter()
        .collect::<Result<Vec<_>, _>>();
    collect_responses(results?)?;
    Ok(())
}

pub async fn do_query(connect_results: impl IntoConnectionResults) -> Result<u32, Error> {
    let ConnectionContext {
        mut connections,
        errors,
    } = ConnectionContext::new(connect_results);
    if let Some(err) = errors.into_iter().next() {
        return Err(err);
    }

    let futures = connections
        .iter_mut()
        .zip(Query4::requests())
        .map(|(connection, request)| run_attested_interaction(connection, request));
    let results = join_all(futures)
        .await
        .into_iter()
        .collect::<Result<Vec<_>, _>>();
    let responses = collect_responses(results?)?;
    Ok(Query4::finalize(&responses)?)
}

async fn run_attested_interaction(
    connection: &mut LabeledConnection,
    request: impl AsRef<[u8]>,
) -> Result<(NextOrClose<Vec<u8>>, &ConnectionLabel), AttestedConnectionError> {
    libsignal_net_infra::ws2::attested::run_attested_interaction(&mut connection.0, request)
        .map_ok(|n| (n, &connection.1))
        .await
}

struct ConnectionContext {
    connections: Vec<LabeledConnection>,
    errors: VecDeque<Error>,
}

impl ConnectionContext {
    fn new<Arr: IntoConnectionResults>(connect_results: Arr) -> Self {
        let mut connections = Vec::with_capacity(Arr::ConnectionResults::N);
        let mut errors = VecDeque::with_capacity(Arr::ConnectionResults::N);
        for connect_result in connect_results.into_connection_results().into_iter() {
            match connect_result {
                Ok((connection, remote_address)) => {
                    connections.push((connection, remote_address));
                }
                Err(err) => errors.push_back(err.into()),
            }
        }
        Self {
            connections,
            errors,
        }
    }
}

#[allow(clippy::result_large_err)]
fn collect_responses<'a>(
    results: impl IntoIterator<Item = (NextOrClose<Vec<u8>>, &'a ConnectionLabel)>,
) -> Result<Vec<Vec<u8>>, Error> {
    results
        .into_iter()
        .map(|(next_or_close, address)| {
            next_or_close.next_or(Error::Protocol(format!("no response from {address}")))
        })
        .collect()
}

#[cfg(test)]
mod test {
    use assert_matches::assert_matches;

    use super::*;
    use crate::enclave::Error;

    struct TestEnv;

    impl PpssSetup for TestEnv {
        type ConnectionResults = NotConnectedResults;
        type ServerIds = [u64; 2];

        fn server_ids() -> Self::ServerIds {
            [1, 2]
        }
    }

    struct NotConnectedResults;

    impl IntoConnectionResults for NotConnectedResults {
        type ConnectionResults = [Result<LabeledConnection, Error>; 2];

        fn into_connection_results(self) -> Self::ConnectionResults {
            [
                Err(Error::ConnectionTimedOut),
                Err(Error::AttestationError(
                    attest::enclave::Error::InvalidBridgeStateError,
                )),
            ]
        }
    }

    #[tokio::test]
    async fn do_backup_fails_with_the_first_error() {
        let backup = do_prepare::<TestEnv>(b"");
        let result = do_backup::<TestEnv>(NotConnectedResults, &backup).await;
        assert_matches!(result, Err(crate::svrb::Error::ConnectionTimedOut));
    }

    #[tokio::test]
    async fn do_restore_fails_with_the_first_error() {
        let result = do_restore::<TestEnv>(NotConnectedResults, b"").await;
        assert_matches!(result, Err(crate::svrb::Error::ConnectionTimedOut));
    }

    #[tokio::test]
    async fn do_query_fails_with_the_first_error() {
        let result = do_query(NotConnectedResults).await;
        assert_matches!(result, Err(crate::svrb::Error::ConnectionTimedOut));
    }

    #[tokio::test]
    async fn do_remove_does_not_fail_on_bad_connections() {
        do_remove(NotConnectedResults)
            .await
            .expect("Should ignore connection errors");
    }
}
