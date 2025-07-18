//
// Copyright 2023 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

package org.signal.libsignal.metadata;

import static org.junit.Assert.assertNotEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import junit.framework.TestCase;
import org.signal.libsignal.metadata.SealedSessionCipher.DecryptionResult;
import org.signal.libsignal.metadata.certificate.CertificateValidator;
import org.signal.libsignal.metadata.certificate.InvalidCertificateException;
import org.signal.libsignal.metadata.certificate.SenderCertificate;
import org.signal.libsignal.metadata.certificate.ServerCertificate;
import org.signal.libsignal.metadata.protocol.UnidentifiedSenderMessageContent;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.InvalidRegistrationIdException;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ServiceId;
import org.signal.libsignal.protocol.ServiceId.InvalidServiceIdException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.UsePqRatchet;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.GroupCipher;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.DecryptionErrorMessage;
import org.signal.libsignal.protocol.message.PlaintextContent;
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.Hex;

public class SealedSessionCipherTest extends TestCase {

  private static SignedPreKeyRecord generateSignedPreKey(
      IdentityKeyPair identityKeyPair, int signedPreKeyId) throws InvalidKeyException {
    ECKeyPair keyPair = ECKeyPair.generate();
    byte[] signature =
        identityKeyPair.getPrivateKey().calculateSignature(keyPair.getPublicKey().serialize());

    return new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature);
  }

  private static KyberPreKeyRecord generateKyberPreKey(
      IdentityKeyPair identityKeyPair, int kyberPreKeyId) throws InvalidKeyException {
    KEMKeyPair keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
    byte[] signature =
        identityKeyPair.getPrivateKey().calculateSignature(keyPair.getPublicKey().serialize());

    return new KyberPreKeyRecord(kyberPreKeyId, System.currentTimeMillis(), keyPair, signature);
  }

  public void testEncryptDecrypt()
      throws UntrustedIdentityException,
          InvalidKeyException,
          InvalidCertificateException,
          InvalidMetadataMessageException,
          ProtocolDuplicateMessageException,
          ProtocolUntrustedIdentityException,
          ProtocolLegacyMessageException,
          ProtocolInvalidKeyException,
          InvalidMetadataVersionException,
          ProtocolInvalidVersionException,
          ProtocolInvalidMessageException,
          ProtocolInvalidKeyIdException,
          ProtocolNoSessionException,
          SelfSendException {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress = new SignalProtocolAddress("+14152222222", 1);

    initializeSessions(aliceStore, bobStore, bobAddress);

    ECKeyPair trustRoot = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    byte[] ciphertext =
        aliceCipher.encrypt(bobAddress, senderCertificate, "smert za smert".getBytes());

    SealedSessionCipher bobCipher =
        new SealedSessionCipher(
            bobStore, UUID.fromString("e80f7bbe-5b94-471e-bd8c-2173654ea3d1"), "+14152222222", 1);

    DecryptionResult plaintext =
        bobCipher.decrypt(
            new CertificateValidator(trustRoot.getPublicKey()),
            ciphertext,
            31335,
            UsePqRatchet.YES);

    assertEquals(new String(plaintext.getPaddedMessage()), "smert za smert");
    assertEquals(plaintext.getSenderUuid(), "9d0652a3-dcc3-4d11-975f-74d61598733f");
    assertEquals(
        plaintext.getSenderAci().toServiceIdString(), "9d0652a3-dcc3-4d11-975f-74d61598733f");
    assertEquals(plaintext.getSenderE164().get(), "+14151111111");
    assertEquals(plaintext.getDeviceId(), 1);
  }

  public void testEncryptDecryptUntrusted() throws Exception {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress = new SignalProtocolAddress("+14152222222", 1);

    initializeSessions(aliceStore, bobStore, bobAddress);

    ECKeyPair trustRoot = ECKeyPair.generate();
    ECKeyPair falseTrustRoot = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            falseTrustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    byte[] ciphertext =
        aliceCipher.encrypt(
            bobAddress, senderCertificate, "\u0438 \u0432\u043E\u0442 \u044F".getBytes());

    SealedSessionCipher bobCipher =
        new SealedSessionCipher(
            bobStore, UUID.fromString("e80f7bbe-5b94-471e-bd8c-2173654ea3d1"), "+14152222222", 1);

    try {
      bobCipher.decrypt(
          new CertificateValidator(trustRoot.getPublicKey()), ciphertext, 31335, UsePqRatchet.YES);
      throw new AssertionError();
    } catch (InvalidMetadataMessageException e) {
      // good
    }
  }

  public void testEncryptDecryptExpired() throws Exception {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress = new SignalProtocolAddress("+14152222222", 1);

    initializeSessions(aliceStore, bobStore, bobAddress);

    ECKeyPair trustRoot = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    byte[] ciphertext =
        aliceCipher.encrypt(
            bobAddress, senderCertificate, "\u0438 \u0432\u043E\u0442 \u044F".getBytes());

    SealedSessionCipher bobCipher =
        new SealedSessionCipher(
            bobStore, UUID.fromString("e80f7bbe-5b94-471e-bd8c-2173654ea3d1"), "+14152222222", 1);

    try {
      bobCipher.decrypt(
          new CertificateValidator(trustRoot.getPublicKey()), ciphertext, 31338, UsePqRatchet.YES);
      throw new AssertionError();
    } catch (InvalidMetadataMessageException e) {
      // good
    }
  }

  public void testEncryptFromWrongIdentity() throws Exception {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress = new SignalProtocolAddress("+14152222222", 1);

    initializeSessions(aliceStore, bobStore, bobAddress);

    ECKeyPair trustRoot = ECKeyPair.generate();
    ECKeyPair randomKeyPair = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            randomKeyPair.getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    byte[] ciphertext =
        aliceCipher.encrypt(bobAddress, senderCertificate, "smert za smert".getBytes());

    SealedSessionCipher bobCipher =
        new SealedSessionCipher(
            bobStore, UUID.fromString("e80f7bbe-5b94-471e-bd8c-2173654ea3d1"), "+14152222222", 1);

    try {
      bobCipher.decrypt(
          new CertificateValidator(trustRoot.getPublicKey()), ciphertext, 31335, UsePqRatchet.YES);
    } catch (InvalidMetadataMessageException e) {
      // good
    }
  }

  public void testEncryptDecryptGroup()
      throws UntrustedIdentityException,
          InvalidKeyException,
          InvalidCertificateException,
          InvalidMessageException,
          InvalidVersionException,
          InvalidMetadataMessageException,
          InvalidRegistrationIdException,
          LegacyMessageException,
          NoSessionException,
          ProtocolDuplicateMessageException,
          ProtocolUntrustedIdentityException,
          ProtocolLegacyMessageException,
          ProtocolInvalidKeyException,
          InvalidMetadataVersionException,
          ProtocolInvalidVersionException,
          ProtocolInvalidMessageException,
          ProtocolInvalidKeyIdException,
          ProtocolNoSessionException,
          SelfSendException {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress =
        new SignalProtocolAddress("e80f7bbe-5b94-471e-bd8c-2173654ea3d1", 1);

    initializeSessions(aliceStore, bobStore, bobAddress);

    ECKeyPair trustRoot = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    SignalProtocolAddress senderAddress =
        new SignalProtocolAddress("9d0652a3-dcc3-4d11-975f-74d61598733f", 1);
    UUID distributionId = UUID.fromString("d1d1d1d1-7000-11eb-b32a-33b8a8a487a6");

    SealedSessionCipher bobCipher =
        new SealedSessionCipher(
            bobStore, UUID.fromString("e80f7bbe-5b94-471e-bd8c-2173654ea3d1"), "+14152222222", 1);

    GroupSessionBuilder aliceSessionBuilder = new GroupSessionBuilder(aliceStore);
    GroupSessionBuilder bobSessionBuilder = new GroupSessionBuilder(bobStore);

    GroupCipher aliceGroupCipher = new GroupCipher(aliceStore, senderAddress);
    GroupCipher bobGroupCipher = new GroupCipher(bobStore, senderAddress);

    SenderKeyDistributionMessage sentAliceDistributionMessage =
        aliceSessionBuilder.create(senderAddress, distributionId);
    SenderKeyDistributionMessage receivedAliceDistributionMessage =
        new SenderKeyDistributionMessage(sentAliceDistributionMessage.serialize());
    bobSessionBuilder.process(senderAddress, receivedAliceDistributionMessage);

    CiphertextMessage ciphertextFromAlice =
        aliceGroupCipher.encrypt(distributionId, "smert ze smert".getBytes());

    UnidentifiedSenderMessageContent usmcFromAlice =
        new UnidentifiedSenderMessageContent(
            ciphertextFromAlice,
            senderCertificate,
            UnidentifiedSenderMessageContent.CONTENT_HINT_IMPLICIT,
            Optional.of(new byte[] {42, 43}));

    byte[] aliceMessage =
        aliceCipher.multiRecipientEncrypt(Arrays.asList(bobAddress), usmcFromAlice);
    byte[] bobMessage = SealedSessionCipher.multiRecipientMessageForSingleRecipient(aliceMessage);

    DecryptionResult plaintext =
        bobCipher.decrypt(
            new CertificateValidator(trustRoot.getPublicKey()),
            bobMessage,
            31335,
            UsePqRatchet.YES);

    assertEquals(new String(plaintext.getPaddedMessage()), "smert ze smert");
    assertEquals(plaintext.getSenderUuid(), "9d0652a3-dcc3-4d11-975f-74d61598733f");
    assertEquals(plaintext.getSenderE164().get(), "+14151111111");
    assertEquals(plaintext.getDeviceId(), 1);
    assertTrue(Arrays.equals(plaintext.getGroupId().get(), new byte[] {42, 43}));
  }

  public void testEncryptGroupWithBadRegistrationId()
      throws UntrustedIdentityException,
          InvalidKeyException,
          InvalidCertificateException,
          InvalidMessageException,
          InvalidMetadataMessageException,
          InvalidRegistrationIdException,
          LegacyMessageException,
          NoSessionException,
          ProtocolDuplicateMessageException,
          ProtocolUntrustedIdentityException,
          ProtocolLegacyMessageException,
          ProtocolInvalidKeyException,
          InvalidMetadataVersionException,
          ProtocolInvalidVersionException,
          ProtocolInvalidMessageException,
          ProtocolInvalidKeyIdException,
          ProtocolNoSessionException,
          SelfSendException {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress =
        new SignalProtocolAddress("e80f7bbe-5b94-471e-bd8c-2173654ea3d1", 1);

    ECKeyPair bobPreKey = ECKeyPair.generate();
    IdentityKeyPair bobIdentityKey = bobStore.getIdentityKeyPair();
    SignedPreKeyRecord bobSignedPreKey = generateSignedPreKey(bobIdentityKey, 2);
    KyberPreKeyRecord bobKyberPreKey = generateKyberPreKey(bobIdentityKey, 12);

    PreKeyBundle bobBundle =
        new PreKeyBundle(
            0x4000,
            1,
            1,
            bobPreKey.getPublicKey(),
            2,
            bobSignedPreKey.getKeyPair().getPublicKey(),
            bobSignedPreKey.getSignature(),
            bobIdentityKey.getPublicKey(),
            12,
            bobKyberPreKey.getKeyPair().getPublicKey(),
            bobKyberPreKey.getSignature());
    SessionBuilder aliceSessionBuilder = new SessionBuilder(aliceStore, bobAddress);
    aliceSessionBuilder.process(bobBundle, UsePqRatchet.YES);

    ECKeyPair trustRoot = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    SignalProtocolAddress senderAddress =
        new SignalProtocolAddress("9d0652a3-dcc3-4d11-975f-74d61598733f", 1);
    UUID distributionId = UUID.fromString("d1d1d1d1-7000-11eb-b32a-33b8a8a487a6");

    GroupSessionBuilder aliceGroupSessionBuilder = new GroupSessionBuilder(aliceStore);
    SenderKeyDistributionMessage sentAliceDistributionMessage =
        aliceGroupSessionBuilder.create(senderAddress, distributionId);

    GroupCipher aliceGroupCipher = new GroupCipher(aliceStore, senderAddress);
    CiphertextMessage ciphertextFromAlice =
        aliceGroupCipher.encrypt(distributionId, "smert ze smert".getBytes());

    UnidentifiedSenderMessageContent usmcFromAlice =
        new UnidentifiedSenderMessageContent(
            ciphertextFromAlice,
            senderCertificate,
            UnidentifiedSenderMessageContent.CONTENT_HINT_IMPLICIT,
            Optional.of(new byte[] {42, 43}));

    try {
      byte[] aliceMessage =
          aliceCipher.multiRecipientEncrypt(Arrays.asList(bobAddress), usmcFromAlice);
      fail("should have thrown");
    } catch (InvalidRegistrationIdException e) {
      assertEquals(e.getAddress(), bobAddress);
    }
  }

  public void testEncryptGroupWithManyRecipients()
      throws UntrustedIdentityException,
          InvalidKeyException,
          InvalidCertificateException,
          InvalidMessageException,
          InvalidMetadataMessageException,
          InvalidRegistrationIdException,
          LegacyMessageException,
          NoSessionException,
          ProtocolDuplicateMessageException,
          ProtocolUntrustedIdentityException,
          ProtocolLegacyMessageException,
          ProtocolInvalidKeyException,
          InvalidMetadataVersionException,
          ProtocolInvalidVersionException,
          ProtocolInvalidMessageException,
          ProtocolInvalidKeyIdException,
          ProtocolNoSessionException,
          SelfSendException {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore carolStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress =
        new SignalProtocolAddress("e80f7bbe-5b94-471e-bd8c-2173654ea3d1", 1);
    SignalProtocolAddress carolAddress =
        new SignalProtocolAddress("38381c3b-2606-4ca7-9310-7cb927f2ab4a", 1);

    ECKeyPair bobPreKey = ECKeyPair.generate();
    IdentityKeyPair bobIdentityKey = bobStore.getIdentityKeyPair();
    SignedPreKeyRecord bobSignedPreKey = generateSignedPreKey(bobIdentityKey, 2);
    KyberPreKeyRecord bobKyberPreKey = generateKyberPreKey(bobIdentityKey, 12);

    PreKeyBundle bobBundle =
        new PreKeyBundle(
            0x1234,
            1,
            1,
            bobPreKey.getPublicKey(),
            2,
            bobSignedPreKey.getKeyPair().getPublicKey(),
            bobSignedPreKey.getSignature(),
            bobIdentityKey.getPublicKey(),
            12,
            bobKyberPreKey.getKeyPair().getPublicKey(),
            bobKyberPreKey.getSignature());
    SessionBuilder aliceSessionBuilderForBob = new SessionBuilder(aliceStore, bobAddress);
    aliceSessionBuilderForBob.process(bobBundle, UsePqRatchet.YES);

    ECKeyPair carolPreKey = ECKeyPair.generate();
    IdentityKeyPair carolIdentityKey = carolStore.getIdentityKeyPair();
    SignedPreKeyRecord carolSignedPreKey = generateSignedPreKey(carolIdentityKey, 2);
    KyberPreKeyRecord carolKyberPreKey = generateKyberPreKey(carolIdentityKey, 12);

    PreKeyBundle carolBundle =
        new PreKeyBundle(
            0x1111,
            1,
            1,
            carolPreKey.getPublicKey(),
            2,
            carolSignedPreKey.getKeyPair().getPublicKey(),
            carolSignedPreKey.getSignature(),
            carolIdentityKey.getPublicKey(),
            12,
            carolKyberPreKey.getKeyPair().getPublicKey(),
            carolKyberPreKey.getSignature());
    SessionBuilder aliceSessionBuilderForCarol = new SessionBuilder(aliceStore, carolAddress);
    aliceSessionBuilderForCarol.process(carolBundle, UsePqRatchet.YES);

    ECKeyPair trustRoot = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    SignalProtocolAddress senderAddress =
        new SignalProtocolAddress("9d0652a3-dcc3-4d11-975f-74d61598733f", 1);
    UUID distributionId = UUID.fromString("d1d1d1d1-7000-11eb-b32a-33b8a8a487a6");

    GroupSessionBuilder aliceGroupSessionBuilder = new GroupSessionBuilder(aliceStore);
    SenderKeyDistributionMessage sentAliceDistributionMessage =
        aliceGroupSessionBuilder.create(senderAddress, distributionId);

    GroupCipher aliceGroupCipher = new GroupCipher(aliceStore, senderAddress);
    CiphertextMessage ciphertextFromAlice =
        aliceGroupCipher.encrypt(distributionId, "smert ze smert".getBytes());

    UnidentifiedSenderMessageContent usmcFromAlice =
        new UnidentifiedSenderMessageContent(
            ciphertextFromAlice,
            senderCertificate,
            UnidentifiedSenderMessageContent.CONTENT_HINT_IMPLICIT,
            Optional.of(new byte[] {42, 43}));

    ArrayList<SignalProtocolAddress> addresses = new ArrayList<>();
    for (int i = 0; i < 1000; ++i) {
      // Alternate between the two to avoid peephole optimizations.
      addresses.add(bobAddress);
      addresses.add(carolAddress);
    }

    // Just check that we don't throw an error or crash.
    byte[] aliceMessage = aliceCipher.multiRecipientEncrypt(addresses, usmcFromAlice);
  }

  public void testEncryptGroupWithMissingSession()
      throws UntrustedIdentityException,
          InvalidKeyException,
          InvalidCertificateException,
          InvalidMessageException,
          InvalidMetadataMessageException,
          InvalidRegistrationIdException,
          LegacyMessageException,
          NoSessionException,
          ProtocolDuplicateMessageException,
          ProtocolUntrustedIdentityException,
          ProtocolLegacyMessageException,
          ProtocolInvalidKeyException,
          InvalidMetadataVersionException,
          ProtocolInvalidVersionException,
          ProtocolInvalidMessageException,
          ProtocolInvalidKeyIdException,
          ProtocolNoSessionException,
          SelfSendException {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress =
        new SignalProtocolAddress("e80f7bbe-5b94-471e-bd8c-2173654ea3d1", 1);
    SignalProtocolAddress carolAddress =
        new SignalProtocolAddress("38381c3b-2606-4ca7-9310-7cb927f2ab4a", 1);

    ECKeyPair bobPreKey = ECKeyPair.generate();
    IdentityKeyPair bobIdentityKey = bobStore.getIdentityKeyPair();
    SignedPreKeyRecord bobSignedPreKey = generateSignedPreKey(bobIdentityKey, 2);
    KyberPreKeyRecord bobKyberPreKey = generateKyberPreKey(bobIdentityKey, 12);

    PreKeyBundle bobBundle =
        new PreKeyBundle(
            0x1234,
            1,
            1,
            bobPreKey.getPublicKey(),
            2,
            bobSignedPreKey.getKeyPair().getPublicKey(),
            bobSignedPreKey.getSignature(),
            bobIdentityKey.getPublicKey(),
            12,
            bobKyberPreKey.getKeyPair().getPublicKey(),
            bobKyberPreKey.getSignature());
    SessionBuilder aliceSessionBuilderForBob = new SessionBuilder(aliceStore, bobAddress);
    aliceSessionBuilderForBob.process(bobBundle, UsePqRatchet.YES);

    ECKeyPair trustRoot = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    SignalProtocolAddress senderAddress =
        new SignalProtocolAddress("9d0652a3-dcc3-4d11-975f-74d61598733f", 1);
    UUID distributionId = UUID.fromString("d1d1d1d1-7000-11eb-b32a-33b8a8a487a6");

    GroupSessionBuilder aliceGroupSessionBuilder = new GroupSessionBuilder(aliceStore);
    SenderKeyDistributionMessage sentAliceDistributionMessage =
        aliceGroupSessionBuilder.create(senderAddress, distributionId);

    GroupCipher aliceGroupCipher = new GroupCipher(aliceStore, senderAddress);
    CiphertextMessage ciphertextFromAlice =
        aliceGroupCipher.encrypt(distributionId, "smert ze smert".getBytes());

    UnidentifiedSenderMessageContent usmcFromAlice =
        new UnidentifiedSenderMessageContent(
            ciphertextFromAlice,
            senderCertificate,
            UnidentifiedSenderMessageContent.CONTENT_HINT_IMPLICIT,
            Optional.of(new byte[] {42, 43}));

    ArrayList<SignalProtocolAddress> addresses = new ArrayList<>();
    for (int i = 0; i < 1000; ++i) {
      // Alternate between the two to avoid peephole optimizations.
      addresses.add(bobAddress);
      addresses.add(carolAddress);
    }

    // Just check that we don't throw an error or crash.
    try {
      aliceCipher.multiRecipientEncrypt(addresses, usmcFromAlice);
    } catch (NoSessionException e) {
      assertEquals(e.getAddress(), carolAddress);
    }
  }

  public void testEncryptGroupWithExcludedRecipients()
      throws UntrustedIdentityException,
          InvalidKeyException,
          InvalidCertificateException,
          InvalidMessageException,
          InvalidMetadataMessageException,
          InvalidRegistrationIdException,
          InvalidServiceIdException,
          LegacyMessageException,
          NoSessionException,
          ProtocolDuplicateMessageException,
          ProtocolUntrustedIdentityException,
          ProtocolLegacyMessageException,
          ProtocolInvalidKeyException,
          InvalidMetadataVersionException,
          ProtocolInvalidVersionException,
          ProtocolInvalidMessageException,
          ProtocolInvalidKeyIdException,
          ProtocolNoSessionException,
          SelfSendException {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore carolStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress =
        new SignalProtocolAddress("e80f7bbe-5b94-471e-bd8c-2173654ea3d1", 1);
    SignalProtocolAddress carolAddress =
        new SignalProtocolAddress("38381c3b-2606-4ca7-9310-7cb927f2ab4a", 1);

    ECKeyPair bobPreKey = ECKeyPair.generate();
    IdentityKeyPair bobIdentityKey = bobStore.getIdentityKeyPair();
    SignedPreKeyRecord bobSignedPreKey = generateSignedPreKey(bobIdentityKey, 2);
    KyberPreKeyRecord bobKyberPreKey = generateKyberPreKey(bobIdentityKey, 12);

    PreKeyBundle bobBundle =
        new PreKeyBundle(
            0x1234,
            1,
            1,
            bobPreKey.getPublicKey(),
            2,
            bobSignedPreKey.getKeyPair().getPublicKey(),
            bobSignedPreKey.getSignature(),
            bobIdentityKey.getPublicKey(),
            12,
            bobKyberPreKey.getKeyPair().getPublicKey(),
            bobKyberPreKey.getSignature());
    SessionBuilder aliceSessionBuilderForBob = new SessionBuilder(aliceStore, bobAddress);
    aliceSessionBuilderForBob.process(bobBundle, UsePqRatchet.YES);

    ECKeyPair carolPreKey = ECKeyPair.generate();
    IdentityKeyPair carolIdentityKey = carolStore.getIdentityKeyPair();
    SignedPreKeyRecord carolSignedPreKey = generateSignedPreKey(carolIdentityKey, 2);
    KyberPreKeyRecord carolKyberPreKey = generateKyberPreKey(carolIdentityKey, 12);

    PreKeyBundle carolBundle =
        new PreKeyBundle(
            0x1111,
            1,
            1,
            carolPreKey.getPublicKey(),
            2,
            carolSignedPreKey.getKeyPair().getPublicKey(),
            carolSignedPreKey.getSignature(),
            carolIdentityKey.getPublicKey(),
            12,
            carolKyberPreKey.getKeyPair().getPublicKey(),
            carolKyberPreKey.getSignature());
    SessionBuilder aliceSessionBuilderForCarol = new SessionBuilder(aliceStore, carolAddress);
    aliceSessionBuilderForCarol.process(carolBundle, UsePqRatchet.YES);

    ECKeyPair trustRoot = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    SignalProtocolAddress senderAddress =
        new SignalProtocolAddress("9d0652a3-dcc3-4d11-975f-74d61598733f", 1);
    UUID distributionId = UUID.fromString("d1d1d1d1-7000-11eb-b32a-33b8a8a487a6");

    GroupSessionBuilder aliceGroupSessionBuilder = new GroupSessionBuilder(aliceStore);
    SenderKeyDistributionMessage sentAliceDistributionMessage =
        aliceGroupSessionBuilder.create(senderAddress, distributionId);

    GroupCipher aliceGroupCipher = new GroupCipher(aliceStore, senderAddress);
    CiphertextMessage ciphertextFromAlice =
        aliceGroupCipher.encrypt(distributionId, "smert ze smert".getBytes());

    UnidentifiedSenderMessageContent usmcFromAlice =
        new UnidentifiedSenderMessageContent(
            ciphertextFromAlice,
            senderCertificate,
            UnidentifiedSenderMessageContent.CONTENT_HINT_IMPLICIT,
            Optional.of(new byte[] {42, 43}));

    ServiceId eveServiceId = ServiceId.parseFromString("3f0f4734-e331-4434-bd4f-6d8f6ea6dcc7");
    ServiceId malloryServiceId = ServiceId.parseFromString("5d088142-6fd7-4dbd-af00-fdda1b3ce988");

    byte[] aliceMessage =
        aliceCipher.multiRecipientEncrypt(
            Arrays.asList(bobAddress, carolAddress),
            usmcFromAlice,
            Arrays.asList(eveServiceId, malloryServiceId));

    // Clients can't directly parse arbitrary SSv2 SentMessages, so just check that it contains
    // the excluded recipient service IDs followed by a device ID of 0.
    String hexEncodedSentMessage = Hex.toStringCondensed(aliceMessage);

    int indexOfE =
        hexEncodedSentMessage.indexOf(
            Hex.toStringCondensed(eveServiceId.toServiceIdFixedWidthBinary()));
    assertNotEquals(-1, indexOfE);
    assertEquals(0, aliceMessage[indexOfE / 2 + 17]);

    int indexOfM =
        hexEncodedSentMessage.indexOf(
            Hex.toStringCondensed(malloryServiceId.toServiceIdFixedWidthBinary()));
    assertNotEquals(-1, indexOfM);
    assertEquals(0, aliceMessage[indexOfM / 2 + 17]);
  }

  public void testProtocolException()
      throws UntrustedIdentityException,
          InvalidKeyException,
          InvalidCertificateException,
          InvalidMessageException,
          InvalidMetadataMessageException,
          InvalidRegistrationIdException,
          LegacyMessageException,
          NoSessionException,
          ProtocolDuplicateMessageException,
          ProtocolUntrustedIdentityException,
          ProtocolLegacyMessageException,
          ProtocolInvalidKeyException,
          InvalidMetadataVersionException,
          ProtocolInvalidVersionException,
          ProtocolInvalidMessageException,
          ProtocolInvalidKeyIdException,
          ProtocolNoSessionException,
          SelfSendException {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress =
        new SignalProtocolAddress("e80f7bbe-5b94-471e-bd8c-2173654ea3d1", 1);

    initializeSessions(aliceStore, bobStore, bobAddress);

    ECKeyPair trustRoot = ECKeyPair.generate();
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    SignalProtocolAddress senderAddress =
        new SignalProtocolAddress("9d0652a3-dcc3-4d11-975f-74d61598733f", 1);
    UUID distributionId = UUID.fromString("d1d1d1d1-7000-11eb-b32a-33b8a8a487a6");

    SealedSessionCipher bobCipher =
        new SealedSessionCipher(
            bobStore, UUID.fromString("e80f7bbe-5b94-471e-bd8c-2173654ea3d1"), "+14152222222", 1);

    GroupSessionBuilder aliceSessionBuilder = new GroupSessionBuilder(aliceStore);
    GroupSessionBuilder bobSessionBuilder = new GroupSessionBuilder(bobStore);

    GroupCipher aliceGroupCipher = new GroupCipher(aliceStore, senderAddress);
    GroupCipher bobGroupCipher = new GroupCipher(bobStore, senderAddress);

    // Send a group message without sending the distribution ID first.
    aliceSessionBuilder.create(senderAddress, distributionId);
    CiphertextMessage ciphertextFromAlice =
        aliceGroupCipher.encrypt(distributionId, "smert ze smert".getBytes());

    UnidentifiedSenderMessageContent usmcFromAlice =
        new UnidentifiedSenderMessageContent(
            ciphertextFromAlice,
            senderCertificate,
            UnidentifiedSenderMessageContent.CONTENT_HINT_RESENDABLE,
            Optional.of(new byte[] {42, 1}));

    byte[] aliceMessage =
        aliceCipher.multiRecipientEncrypt(Arrays.asList(bobAddress), usmcFromAlice);
    byte[] bobMessage = SealedSessionCipher.multiRecipientMessageForSingleRecipient(aliceMessage);

    try {
      bobCipher.decrypt(
          new CertificateValidator(trustRoot.getPublicKey()), bobMessage, 31335, UsePqRatchet.YES);
      fail("should have thrown");
    } catch (ProtocolNoSessionException e) {
      assertEquals(e.getSender(), "9d0652a3-dcc3-4d11-975f-74d61598733f");
      assertEquals(e.getSenderAci().toServiceIdString(), "9d0652a3-dcc3-4d11-975f-74d61598733f");
      assertEquals(e.getSenderDevice(), 1);
      assertEquals(e.getContentHint(), UnidentifiedSenderMessageContent.CONTENT_HINT_RESENDABLE);
      assertEquals(
          Hex.toStringCondensed(e.getGroupId().get()), Hex.toStringCondensed(new byte[] {42, 1}));
    }
  }

  public void testDecryptionErrorMessage()
      throws InvalidCertificateException,
          InvalidKeyException,
          InvalidMessageException,
          InvalidMetadataMessageException,
          InvalidMetadataVersionException,
          NoSessionException,
          ProtocolDuplicateMessageException,
          ProtocolInvalidKeyException,
          ProtocolInvalidKeyIdException,
          ProtocolInvalidMessageException,
          ProtocolInvalidVersionException,
          ProtocolLegacyMessageException,
          ProtocolNoSessionException,
          ProtocolUntrustedIdentityException,
          SelfSendException,
          UntrustedIdentityException {
    TestInMemorySignalProtocolStore aliceStore = new TestInMemorySignalProtocolStore();
    TestInMemorySignalProtocolStore bobStore = new TestInMemorySignalProtocolStore();
    SignalProtocolAddress bobAddress = new SignalProtocolAddress("+14152222222", 1);

    initializeSessions(aliceStore, bobStore, bobAddress);

    ECKeyPair trustRoot = ECKeyPair.generate();
    CertificateValidator certificateValidator = new CertificateValidator(trustRoot.getPublicKey());
    SenderCertificate senderCertificate =
        createCertificateFor(
            trustRoot,
            UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"),
            "+14151111111",
            1,
            aliceStore.getIdentityKeyPair().getPublicKey().getPublicKey(),
            31337);
    SealedSessionCipher aliceCipher =
        new SealedSessionCipher(
            aliceStore, UUID.fromString("9d0652a3-dcc3-4d11-975f-74d61598733f"), "+14151111111", 1);

    // Send a message from Alice to Bob to set up the session.
    byte[] ciphertext =
        aliceCipher.encrypt(bobAddress, senderCertificate, "smert za smert".getBytes());

    SealedSessionCipher bobCipher =
        new SealedSessionCipher(
            bobStore, UUID.fromString("e80f7bbe-5b94-471e-bd8c-2173654ea3d1"), "+14152222222", 1);

    bobCipher.decrypt(certificateValidator, ciphertext, 31335, UsePqRatchet.YES);

    // Pretend Bob's reply fails to decrypt.
    SignalProtocolAddress aliceAddress =
        new SignalProtocolAddress("9d0652a3-dcc3-4d11-975f-74d61598733f", 1);
    SessionCipher bobUnsealedCipher = new SessionCipher(bobStore, aliceAddress);
    CiphertextMessage bobMessage = bobUnsealedCipher.encrypt("reply".getBytes());

    DecryptionErrorMessage errorMessage =
        DecryptionErrorMessage.forOriginalMessage(
            bobMessage.serialize(), bobMessage.getType(), 408, bobAddress.getDeviceId());
    PlaintextContent errorMessageContent = new PlaintextContent(errorMessage);
    UnidentifiedSenderMessageContent errorMessageUsmc =
        new UnidentifiedSenderMessageContent(
            errorMessageContent,
            senderCertificate,
            UnidentifiedSenderMessageContent.CONTENT_HINT_IMPLICIT,
            Optional.<byte[]>empty());
    byte[] errorMessageCiphertext = aliceCipher.encrypt(bobAddress, errorMessageUsmc);

    DecryptionResult result =
        bobCipher.decrypt(certificateValidator, errorMessageCiphertext, 31335, UsePqRatchet.YES);
    DecryptionErrorMessage bobErrorMessage =
        DecryptionErrorMessage.extractFromSerializedContent(result.getPaddedMessage());
    assertEquals(bobErrorMessage.getTimestamp(), 408);
    assertEquals(bobErrorMessage.getDeviceId(), bobAddress.getDeviceId());

    SessionRecord bobSessionWithAlice = bobStore.loadSession(aliceAddress);
    assert (bobSessionWithAlice.currentRatchetKeyMatches(bobErrorMessage.getRatchetKey().get()));
  }

  private SenderCertificate createCertificateFor(
      ECKeyPair trustRoot,
      UUID uuid,
      String e164,
      int deviceId,
      ECPublicKey identityKey,
      long expires)
      throws InvalidKeyException, InvalidCertificateException {
    ECKeyPair serverKey = ECKeyPair.generate();
    ServerCertificate serverCertificate =
        new ServerCertificate(trustRoot.getPrivateKey(), 1, serverKey.getPublicKey());
    return serverCertificate.issue(
        serverKey.getPrivateKey(),
        uuid.toString(),
        Optional.ofNullable(e164),
        deviceId,
        identityKey,
        expires);
  }

  private void initializeSessions(
      TestInMemorySignalProtocolStore aliceStore,
      TestInMemorySignalProtocolStore bobStore,
      SignalProtocolAddress bobAddress)
      throws InvalidKeyException, UntrustedIdentityException {
    ECKeyPair bobPreKey = ECKeyPair.generate();
    IdentityKeyPair bobIdentityKey = bobStore.getIdentityKeyPair();
    SignedPreKeyRecord bobSignedPreKey = generateSignedPreKey(bobIdentityKey, 2);
    KyberPreKeyRecord bobKyberPreKey = generateKyberPreKey(bobIdentityKey, 12);

    PreKeyBundle bobBundle =
        new PreKeyBundle(
            1,
            1,
            1,
            bobPreKey.getPublicKey(),
            2,
            bobSignedPreKey.getKeyPair().getPublicKey(),
            bobSignedPreKey.getSignature(),
            bobIdentityKey.getPublicKey(),
            12,
            bobKyberPreKey.getKeyPair().getPublicKey(),
            bobKyberPreKey.getSignature());
    SessionBuilder aliceSessionBuilder = new SessionBuilder(aliceStore, bobAddress);
    aliceSessionBuilder.process(bobBundle, UsePqRatchet.YES);

    bobStore.storeSignedPreKey(2, bobSignedPreKey);
    bobStore.storeKyberPreKey(12, bobKyberPreKey);
    bobStore.storePreKey(1, new PreKeyRecord(1, bobPreKey));
  }
}
