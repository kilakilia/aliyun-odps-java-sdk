package com.aliyun.odps.mapred.bridge.utils;


import apsara.odps.TypesProtos;
import com.aliyun.odps.Column;
import com.aliyun.odps.OdpsType;


public class TypeUtils {

  public static TypesProtos.Type getLotTypeFromColumn(Column t) {
    OdpsType tp = t.getType();
    if (tp == OdpsType.ARRAY) {
      return getArrayType(t.getGenericTypeList().get(0));
    } else if (tp == OdpsType.MAP) {
      return getMapType(t.getGenericTypeList().get(0), t.getGenericTypeList().get(1));
    } else {
      return getPrimitiveLotTypeFromOdpsType(tp);
    }
  }

  private static TypesProtos.Type getPrimitiveLotTypeFromOdpsType(OdpsType t) {
    TypesProtos.Type r;
    switch (t) {
      case BIGINT:
        r = TypesProtos.Type.Integer;
        break;
      case STRING:
        r = TypesProtos.Type.String;
        break;
      case DOUBLE:
        r = TypesProtos.Type.Double;
        break;
      case BOOLEAN:
        r = TypesProtos.Type.Bool;
        break;
      case DATETIME:
        r = TypesProtos.Type.Datetime;
        break;
      case DECIMAL:
        r = TypesProtos.Type.Decimal;
        break;
      default:
        throw new IllegalArgumentException("unknown type:" + t);
    }
    return r;
  }

  private static TypesProtos.Type getArrayType(OdpsType elementType) {
    String eleType = getPrimitiveLotTypeFromOdpsType(elementType).name();
    return TypesProtos.Type.valueOf("Array" + eleType);
  }

  private static TypesProtos.Type getMapType(OdpsType keyType, OdpsType valueType) {
    String kType = getPrimitiveLotTypeFromOdpsType(keyType).name();
    String vType = getPrimitiveLotTypeFromOdpsType(valueType).name();
    return TypesProtos.Type.valueOf("Map" + kType + vType);
  }

  public static Column createColumnWithNewName(String name, Column src) {
    Column col = new Column(name, src.getType(), src.getComment());
    col.setGenericTypeList(src.getGenericTypeList());
    return col;
  }

  public static Column cloneColumn(Column src) {
    Column col = new Column(src.getName(), src.getType(), src.getComment());
    col.setGenericTypeList(src.getGenericTypeList());
    return col;
  }
}
