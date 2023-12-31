/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.security.wycheproof;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Test;
import org.junit.Ignore;
import android.security.keystore.KeyProtection;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import android.keystore.cts.util.KeyStoreUtil;

/** 
 * CipherOutputStream tests
 *
 * <p>CipherOutputStream is a class that is basically unsuitable for authenticated encryption
 * and hence should be avoided whenever possible. The class is unsuitable, because the interface
 * does not provide a method to tell the caller when decryption failed. I.e. the specification
 * now explicitly claims that it catches exceptions thrown by the Cipher class such as
 * BadPaddingException and that it does not rethrow them.
 * http://www.oracle.com/technetwork/java/javase/8u171-relnotes-4308888.html
 *
 * <p>The Jdk implementation has the property that no unauthenticated plaintext is released.
 * In the case of an authentication failure the implementation simply returns an empty plaintext.
 * This allows a trivial attack where the attacker substitutes any message with an empty message. 
 *
 * <p>The tests in this class have been adapted to this unfortunate situation. testEmptyPlaintext 
 * checks whether corrupting the tag of an empty message is detected. This test currently fails.
 * All other tests run under the assumption that returning an empty plaintext is acceptable
 * behaviour, so that the tests are able to catch additional problems.
 */

public class CipherOutputStreamTest {
  private static final String EXPECTED_PROVIDER_NAME = TestUtil.EXPECTED_CRYPTO_OP_PROVIDER_NAME;
  static final SecureRandom rand = new SecureRandom();

  @After
  public void tearDown() throws Exception {
    KeyStoreUtil.cleanUpKeyStore();
  }

  static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    rand.nextBytes(bytes);
    return bytes;
  }

  static SecretKey randomKey(String algorithm, String alias, int keySizeInBytes,
                             boolean isStrongBox) throws Exception{
      SecretKeySpec keySpec = new SecretKeySpec(randomBytes(keySizeInBytes), "AES");
      KeyStore keyStore = KeyStoreUtil.saveSecretKeyToKeystore(alias, keySpec,
          new KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                  .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                  .setRandomizedEncryptionRequired(false)
                  .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                  .setIsStrongBoxBacked(isStrongBox)
                  .build());
      // Key imported, obtain a reference to it.
      return (SecretKey) keyStore.getKey(alias, null);
  }

  static AlgorithmParameterSpec randomParameters(
      String algorithm, int ivSizeInBytes, int tagSizeInBytes) {
    if ("AES/GCM/NoPadding".equals(algorithm) || "AES/EAX/NoPadding".equals(algorithm)) {
      return new GCMParameterSpec(8 * tagSizeInBytes, randomBytes(ivSizeInBytes));
    }
    return null;
  }

  /** Test vectors */
  @SuppressWarnings("InsecureCryptoUsage")
  public static class TestVector {
    public String algorithm;
    public SecretKey key;
    public AlgorithmParameterSpec params;
    public byte[] pt;
    public byte[] aad;
    public byte[] ct;

    public TestVector(
        String algorithm, String alias, int keySize,
        int ivSize, int tagSize, int ptSize, int aadSize, boolean isStrongBox) throws Exception {
      this.algorithm = algorithm;
      this.key = randomKey(algorithm, alias, keySize, isStrongBox);
      this.params = randomParameters(algorithm, ivSize, tagSize);
      this.pt = randomBytes(ptSize);
      this.aad = randomBytes(aadSize);
      Cipher cipher = Cipher.getInstance(algorithm, EXPECTED_PROVIDER_NAME);
      cipher.init(Cipher.ENCRYPT_MODE, this.key, this.params);
      cipher.updateAAD(aad);
      this.ct = cipher.doFinal(pt);
    }
  }

  Iterable<TestVector> getTestVectors(
      String algorithm,
      int[] keySizes,
      int[] ivSizes,
      int[] tagSizes,
      int[] ptSizes,
      int[] aadSizes,
      boolean isStrongBox)
      throws Exception {
    int counter = 0;
    ArrayList<TestVector> result = new ArrayList<TestVector>();
    for (int keySize : keySizes) {
      for (int ivSize : ivSizes) {
        for (int tagSize : tagSizes) {
          for (int ptSize : ptSizes) {
            for (int aadSize : aadSizes) {
              String keyAlias = "Key" + counter++;
              result.add(new TestVector(algorithm, keyAlias, keySize,
                                        ivSize, tagSize, ptSize, aadSize, isStrongBox));
            }
          }
        }
      }
    }
    return result;
  }

  @SuppressWarnings("InsecureCryptoUsage")
  public void testEncrypt(Iterable<TestVector> tests) throws Exception {
    for (TestVector t : tests) {
      Cipher cipher = Cipher.getInstance(t.algorithm, EXPECTED_PROVIDER_NAME);
      cipher.init(Cipher.ENCRYPT_MODE, t.key, t.params);
      cipher.updateAAD(t.aad);
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      CipherOutputStream cos = new CipherOutputStream(os, cipher);
      cos.write(t.pt);
      cos.close();
      assertEquals(TestUtil.bytesToHex(t.ct), TestUtil.bytesToHex(os.toByteArray()));
    }
  }

  @SuppressWarnings("InsecureCryptoUsage")
  public void testDecrypt(Iterable<TestVector> tests) throws Exception {
    for (TestVector t : tests) {
      Cipher cipher = Cipher.getInstance(t.algorithm, EXPECTED_PROVIDER_NAME);
      cipher.init(Cipher.DECRYPT_MODE, t.key, t.params);
      cipher.updateAAD(t.aad);
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      CipherOutputStream cos = new CipherOutputStream(os, cipher);
      cos.write(t.ct);
      cos.close();
      assertEquals(TestUtil.bytesToHex(t.pt), TestUtil.bytesToHex(os.toByteArray()));
    }
  }

  /**
   * Tests decryption of corrupted ciphertext. The test may accept empty plaintext as valid
   * result because of the problem with CipherOutputStream described in the header of this file.
   * @param tests an iterable with valid test vectors, that will be corrupted for the test
   * @param acceptEmptyPlaintext determines whether an empty plaintext instead of an exception
   *     is acceptable.
   */
  @SuppressWarnings("InsecureCryptoUsage")
  public void testCorruptDecrypt(Iterable<TestVector> tests, boolean acceptEmptyPlaintext)
      throws Exception {
    for (TestVector t : tests) {
      Cipher cipher = Cipher.getInstance(t.algorithm, EXPECTED_PROVIDER_NAME);
      cipher.init(Cipher.DECRYPT_MODE, t.key, t.params);
      cipher.updateAAD(t.aad);
      byte[] ct = Arrays.copyOf(t.ct, t.ct.length);
      ct[ct.length - 1] ^= (byte) 1;
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      CipherOutputStream cos = new CipherOutputStream(os, cipher);
      cos.write(ct);
      try {
        // cos.close() should call cipher.doFinal().
        cos.close();
        byte[] decrypted = os.toByteArray();
        // Unfortunately Oracle thinks that returning an empty array is valid behaviour.
        // We accept empty results here, but flag them in the next test, so that we can distinguish
        // between beheviour considered acceptable by Oracle and more serious flaws.
        if (decrypted.length > 0) {
          fail(
              "this should fail; decrypted:"
                  + TestUtil.bytesToHex(decrypted)
                  + " pt: "
                  + TestUtil.bytesToHex(t.pt));
        } else if (decrypted.length == 0 && !acceptEmptyPlaintext) {
          fail("Corrupted ciphertext returns empty plaintext");
        }
      } catch (IOException ex) {
        // expected
      }
    }
  }

  @Test
  public void testAesGcm() throws Exception {
    testAesGcm(false);
  }
  @Test
  public void testAesGcm_StrongBox() throws Exception {
    KeyStoreUtil.assumeStrongBox();
    testAesGcm(true);
  }
  private void testAesGcm(boolean isStrongBox) throws Exception {
    final int[] keySizes = {16, 32};
    final int[] ivSizes = {12};
    final int[] tagSizes = {12, 16};
    final int[] ptSizes = {8, 16, 65, 8100};
    final int[] aadSizes = {0, 8, 24};
    Iterable<TestVector> v =
        getTestVectors("AES/GCM/NoPadding", keySizes,
                ivSizes, tagSizes, ptSizes, aadSizes, isStrongBox);
    testEncrypt(v);
    testDecrypt(v);
    boolean acceptEmptyPlaintext = true;
    testCorruptDecrypt(v, acceptEmptyPlaintext);
  }

  /**
   * Tests the behaviour for corrupt plaintext more strictly than in the tests above.
   * This test does not accept that an implementation returns an empty plaintext when the
   * ciphertext has been corrupted.
   */
  @Test
  public void testEmptyPlaintext() throws Exception {
    testEmptyPlaintext(false);
  }
  @Test
  public void testEmptyPlaintext_StrongBox() throws Exception {
    KeyStoreUtil.assumeStrongBox();
    testEmptyPlaintext(true);
  }
  private void testEmptyPlaintext(boolean isStrongBox) throws Exception {
    final int[] keySizes = {16, 32};
    final int[] ivSizes = {12};
    final int[] tagSizes = {12, 16};
    final int[] ptSizes = {0};
    final int[] aadSizes = {0, 8, 24};
    Iterable<TestVector> v =
        getTestVectors("AES/GCM/NoPadding", keySizes,
                ivSizes, tagSizes, ptSizes, aadSizes, isStrongBox);
    testEncrypt(v);
    testDecrypt(v);
    boolean acceptEmptyPlaintext = false;
    testCorruptDecrypt(v, acceptEmptyPlaintext);
  }

  /** Tests CipherOutputStream with AES-EAX if AES-EAS is supported by the provider. */
  @SuppressWarnings("InsecureCryptoUsage")
  @Test
  @Ignore // Ignored due to AES/EAX algorithm is not supported in AndroidKeyStore
  public void testAesEax() throws Exception {
    final String algorithm = "AES/EAX/NoPadding";
    final int[] keySizes = {16, 32};
    final int[] ivSizes = {12, 16};
    final int[] tagSizes = {12, 16};
    final int[] ptSizes = {8, 16, 65, 8100};
    final int[] aadSizes = {0, 8, 24};
    Iterable<TestVector> v =
        getTestVectors(algorithm, keySizes, ivSizes, tagSizes, ptSizes,
                aadSizes, /*isStrongBox*/ false);
    testEncrypt(v);
    testDecrypt(v);
    boolean acceptEmptyPlaintext = true;
    testCorruptDecrypt(v, acceptEmptyPlaintext);
  }
}
