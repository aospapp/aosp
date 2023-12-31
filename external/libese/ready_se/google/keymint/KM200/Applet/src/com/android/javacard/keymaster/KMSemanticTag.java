package com.android.javacard.keymaster;

import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;

/**
 * KMSemanticTag corresponds to CBOR type of tagged item. The structure is defined as struct{byte
 * SEMANTIC_TAG_TYPE; short length; tag, short ptr }. Tag is INTEGER_TYPE and the possible values
 * are defined here https://www.rfc-editor.org/rfc/rfc7049#section-2.4
 */
public class KMSemanticTag extends KMType {

  public static final short COSE_MAC_SEMANTIC_TAG = (short) 0x0011;
  public static final short ROT_SEMANTIC_TAG = (short) 0x9C41;
  private static KMSemanticTag prototype;

  private KMSemanticTag() {}

  private static KMSemanticTag proto(short ptr) {
    if (prototype == null) {
      prototype = new KMSemanticTag();
    }
    instanceTable[KM_SEMANTIC_TAG_OFFSET] = ptr;
    return prototype;
  }

  // pointer to an empty instance used as expression
  public static short exp(short valuePtr) {
    short ptr = KMType.instance(SEMANTIC_TAG_TYPE, (short) 6);
    Util.setShort(heap, (short) (ptr + TLV_HEADER_SIZE + 2), KMInteger.exp());
    Util.setShort(heap, (short) (ptr + TLV_HEADER_SIZE + 4), valuePtr);
    return ptr;
  }

  public static KMSemanticTag cast(short ptr) {
    if (heap[ptr] != SEMANTIC_TAG_TYPE) {
      ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
    }
    return proto(ptr);
  }

  public static short instance(short tag, short value) {
    if (!isSemanticTagSupported(tag)) {
      ISOException.throwIt(ISO7816.SW_DATA_INVALID);
    }
    // The maximum tag size can be UINT32. Currently, we support
    // only two tags which are short.
    short ptr = KMType.instance(SEMANTIC_TAG_TYPE, (short) 6);
    Util.setShort(heap, (short) (ptr + TLV_HEADER_SIZE + 2), tag);
    Util.setShort(heap, (short) (ptr + TLV_HEADER_SIZE + 4), value);
    return ptr;
  }

  private static boolean isSemanticTagSupported(short tag) {
    tag = KMInteger.cast(tag).getShort();
    switch (tag) {
      case COSE_MAC_SEMANTIC_TAG:
      case ROT_SEMANTIC_TAG:
        break;
      default:
        return false;
    }
    return true;
  }

  public short length() {
    return Util.getShort(heap, (short) (instanceTable[KM_SEMANTIC_TAG_OFFSET] + 1));
  }

  public short getKeyPtr() {
    return Util.getShort(
        heap, (short) (instanceTable[KM_SEMANTIC_TAG_OFFSET] + TLV_HEADER_SIZE + 2));
  }

  public short getValuePtr() {
    return Util.getShort(
        heap, (short) (instanceTable[KM_SEMANTIC_TAG_OFFSET] + TLV_HEADER_SIZE + 4));
  }
}
