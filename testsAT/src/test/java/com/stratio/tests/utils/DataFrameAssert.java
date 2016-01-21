/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.tests.utils;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;

import org.apache.spark.sql.crossdata.ExecutionType;
import org.apache.spark.sql.crossdata.XDDataFrame;
import org.apache.spark.sql.types.Decimal;
import org.assertj.core.api.AbstractAssert;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.Row;
import scala.collection.JavaConverters;
/**
 * Created by hdominguez on 19/10/15.
 */
public class DataFrameAssert extends AbstractAssert<DataFrameAssert, XDDataFrame>{

    /**
     * Generic constructor.
     *
     * @param actual
     */
    public DataFrameAssert(XDDataFrame actual) {
        super(actual, DataFrameAssert.class);
    }

    /**
     * Checks the "DataFrame".
     *
     * @param actual
     * @return DataFrameAssert
     */
    public static DataFrameAssert asserThat(XDDataFrame actual){
        return new DataFrameAssert(actual);
    }

    /**
     * Checks if a DataFrame has an specific length.
     *
     * @param length
     * @return DataFrameAssert
     */
    public DataFrameAssert hasLength(int length){
        if(actual.collect().length != length){
            failWithMessage("Expected DataFrame length to be <%s> but was <%s>", length, actual.collect().length);
        }
        return this;
    }

    public DataFrameAssert equalsMetadata(List<String> firstRow){
        StructField[] actualStructField = actual.schema().fields();
        if(actualStructField.length != firstRow.size()){
            failWithMessage("Expected number of columns to be <%s> but was <%s>", firstRow.size(), actualStructField
                    .length);
        }
        for(int i = 0; i < firstRow.size(); i++){
            String[] columnExpected = firstRow.get(i).split("-");
            if(!columnExpected[0].equals(actualStructField[i].name())){
                failWithMessage("Expected column name to be <%s> but was <%s>",columnExpected[0],actualStructField[i]
                        .name());
            }
            if((!columnExpected[1].equals(actualStructField[i].dataType().typeName())) && (!columnExpected[1].contains
                    ("array"))){
                failWithMessage("Expected type for column <%s> to be <%s> but was <%s>", columnExpected[0],
                        columnExpected[1],actualStructField[i].dataType().typeName());
            }
            if(actualStructField[i].dataType().typeName().equals("array")){
                if(!columnExpected[1].equals(actualStructField[i].dataType().simpleString())){
                    failWithMessage("Expected type for column <%s> to be <%s> but was <%s>", columnExpected[0],
                            columnExpected[1],actualStructField[i].dataType().simpleString());
                }
            }

        }
        return this;
    }

    public DataFrameAssert equalsResultsNative(List<List<String>> table){
        Row[] actualRows = actual.collect(ExecutionType.Native());
        List<String> firstRow = table.get(0);
        boolean isEquals = false;
        for(int i = 0; i < actualRows.length; i++){
            Row actualRow = actualRows[i];
            for(int x = 0; x < actualRow.size(); x++){
                String[] columnExpected = firstRow.get(x).split("-");
                switch(columnExpected[1]){
                    case "boolean":
                        if (!(actualRow.get(x) instanceof Boolean)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Boolean\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(actualRow.getBoolean(x) != (Boolean.parseBoolean(table.get(i+1).get(x)))){
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], Boolean.parseBoolean(table.get(i + 1).get(x)),
                                    actualRow.getBoolean(x));
                        }
                        break;
                    case "byte":
                        if (!(actualRow.get(x) instanceof Byte)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Byte\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(actualRow.getByte(x) != Byte.parseByte(table.get(i+1).get(x))){
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], Byte.parseByte(table.get(i + 1).get(x)),
                                    actualRow.getByte(x));
                        }
                        break;
                    case "date":
                        if (!(actualRow.get(x) instanceof  java.sql.Date)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.sql.Date\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(!actualRow.getDate(x).equals(Date.valueOf(table.get(i + 1).get(x)))){
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], Date.valueOf(table.get(i + 1).get(x)),
                                    actualRow.getDate(x));
                        }
                        break;
                    case "decimal":
                        if (!(actualRow.get(x) instanceof java.math.BigDecimal)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.math.BigDecimal\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(!actualRow.getDecimal(x).equals(Decimal.apply(table.get(i + 1).get(x)))){
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], Decimal.apply(table.get(i + 1).get(x)),
                                    actualRow.getDecimal(x));
                        }
                        break;
                    case "double":
                        if (!(actualRow.get(x) instanceof Double)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Double\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(actualRow.getDouble(x) != (Double.parseDouble(table.get(i + 1).get(x)))) {
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], Double.parseDouble(table.get(i + 1).get(x)),
                                    actualRow.getDouble(x));
                        }
                        break;
                    case "float":
                        if (!(actualRow.get(x) instanceof Float)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Float\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(actualRow.getFloat(x) != (Float.parseFloat(table.get(i + 1).get(x)))) {
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Float.parseFloat(table.get(i + 1).get(x)),
                                actualRow.getFloat(x));
                        }
                        break;
                    case "integer":
                        if (!(actualRow.get(x) instanceof Integer)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Integer\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(actualRow.getInt(x) != Integer.parseInt(table.get(i+1).get(x))){
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], Integer.parseInt(table.get(i+1).get(x)), actualRow.getInt(x));
                        }
                        break;
                    case "long":
                        if (!(actualRow.get(x) instanceof Long)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Long\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(actualRow.getLong(x) != Long.parseLong(table.get(i + 1).get(x))){
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], Long.parseLong(table.get(i + 1).get(x)), actualRow.getLong(x));
                        }
                        break;
                    case "short":
                        if (!(actualRow.get(x) instanceof Short)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Short\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(actualRow.getShort(x) != Short.parseShort(table.get(i + 1).get(x))){
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], Short.parseShort(table.get(i + 1).get(x)), actualRow.getShort(x));
                        }
                        break;
                    case "string":
                        if (!(actualRow.get(x) instanceof String)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.String\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(!actualRow.getString(x).equals(table.get(i+1).get(x))){
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], table.get(i+1).get(x), actualRow.getString(x));
                        }
                        break;
                    case "timestamp":
                        if (!(actualRow.get(x) instanceof java.sql.Timestamp)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be \"java.sql.Timestamp\" "
                                            + "but  was <%s>", i,
                                    columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        if(!actualRow.getTimestamp(x).equals(Timestamp.valueOf(table.get(i + 1).get(x)))){
                            failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                    columnExpected[0], Timestamp.valueOf(table.get(i + 1).get(x)),
                                    actualRow.getTimestamp(x));
                        }
                        break;
                    case "array<string>":
                        if (!(actualRow.get(x) instanceof scala.collection.mutable.ArrayBuffer)){
                            failWithMessage("Expected type for row <%s> for column <%s> to be an \"array\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                        }
                        scala.collection.mutable.ArrayBuffer<String> obtainedResult = (scala.collection.mutable
                                .ArrayBuffer<String>)actualRow.get(x);
                        List<String> obtainedResultList = JavaConverters.asJavaListConverter(obtainedResult).asJava();
                        String[] expectedResult = table.get(i + 1).get(x).split(",");
                        if(obtainedResult.size() != expectedResult.length){
                            failWithMessage("Expected length of array to be <%s> but was <%s>", expectedResult.length,
                                    obtainedResultList
                                    .size());
                        }
                        for(int j = 0; j < obtainedResult.size(); j++){
                            if(!obtainedResultList.get(j).equals(expectedResult[j])) {
                                failWithMessage("Expected value for row <%s> and position <%s> for column <%s> to be "
                                                + "<%s> but was <%s>",
                                        i,j,
                                        columnExpected[0], expectedResult[j],
                                        obtainedResultList.get(j));
                            }
                        }
                        break;
                default:
                    failWithMessage("The type <%s> is not implemented", columnExpected[1]);
                }
            }
        }
        return this;
    }

    public DataFrameAssert equalsResultsSpark(List<List<String>> table){
        Row[] actualRows = actual.collect(ExecutionType.Spark());
        List<String> firstRow = table.get(0);
        boolean isEquals = false;
        for(int i = 0; i < actualRows.length; i++){
            Row actualRow = actualRows[i];
            for(int x = 0; x < actualRow.size(); x++){
                String[] columnExpected = firstRow.get(x).split("-");
                switch(columnExpected[1]){
                case "boolean":
                    if (!(actualRow.get(x) instanceof Boolean)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Boolean\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(actualRow.getBoolean(x) != (Boolean.parseBoolean(table.get(i+1).get(x)))){
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Boolean.parseBoolean(table.get(i + 1).get(x)),
                                actualRow.getBoolean(x));
                    }
                    break;
                case "byte":
                    if (!(actualRow.get(x) instanceof Byte)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Byte\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(actualRow.getByte(x) != Byte.parseByte(table.get(i+1).get(x))){
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Byte.parseByte(table.get(i + 1).get(x)),
                                actualRow.getByte(x));
                    }
                    break;
                case "date":
                    if (!(actualRow.get(x) instanceof  java.sql.Date)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.sql.Date\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(!actualRow.getDate(x).equals(Date.valueOf(table.get(i + 1).get(x)))){
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Date.valueOf(table.get(i + 1).get(x)),
                                actualRow.getDate(x));
                    }
                    break;
                case "decimal":
                    if (!(actualRow.get(x) instanceof java.math.BigDecimal)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.math.BigDecimal\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(!actualRow.getDecimal(x).equals(Decimal.apply(table.get(i + 1).get(x)))){
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Decimal.apply(table.get(i + 1).get(x)),
                                actualRow.getDecimal(x));
                    }
                    break;
                case "double":
                    if (!(actualRow.get(x) instanceof Double)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Double\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(actualRow.getDouble(x) != (Double.parseDouble(table.get(i + 1).get(x)))) {
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Double.parseDouble(table.get(i + 1).get(x)),
                                actualRow.getDouble(x));
                    }
                    break;
                case "float":
                    if (!(actualRow.get(x) instanceof Float)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Float\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(actualRow.getFloat(x) != (Float.parseFloat(table.get(i + 1).get(x)))) {
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Float.parseFloat(table.get(i + 1).get(x)),
                                actualRow.getFloat(x));
                    }
                    break;
                case "integer":
                    if (!(actualRow.get(x) instanceof Integer)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Integer\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(actualRow.getInt(x) != Integer.parseInt(table.get(i+1).get(x))){
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Integer.parseInt(table.get(i+1).get(x)), actualRow.getInt(x));
                    }
                    break;
                case "long":
                    if (!(actualRow.get(x) instanceof Long)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Long\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(actualRow.getLong(x) != Long.parseLong(table.get(i + 1).get(x))){
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Long.parseLong(table.get(i + 1).get(x)), actualRow.getLong(x));
                    }
                    break;
                case "short":
                    if (!(actualRow.get(x) instanceof Short)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Short\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(actualRow.getShort(x) != Short.parseShort(table.get(i + 1).get(x))){
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Short.parseShort(table.get(i + 1).get(x)), actualRow.getShort(x));
                    }
                    break;
                case "string":
                    if (!(actualRow.get(x) instanceof String)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.String\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(!actualRow.getString(x).equals(table.get(i+1).get(x))){
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], table.get(i+1).get(x), actualRow.getString(x));
                    }
                    break;
                case "timestamp":
                    if (!(actualRow.get(x) instanceof java.sql.Timestamp)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be \"java.sql.Timestamp\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    if(!actualRow.getTimestamp(x).equals(Timestamp.valueOf(table.get(i + 1).get(x)))){
                        failWithMessage("Expected value for row <%s> for column <%s> to be <%s> but was <%s>", i,
                                columnExpected[0], Timestamp.valueOf(table.get(i + 1).get(x)),
                                actualRow.getTimestamp(x));
                    }
                    break;
                case "array<string>":
                    if (!(actualRow.get(x) instanceof scala.collection.mutable.ArrayBuffer)){
                        failWithMessage("Expected type for row <%s> for column <%s> to be an \"array\" "
                                        + "but  was <%s>", i,
                                columnExpected[0], actualRow.get(x).getClass().getName());
                    }
                    scala.collection.mutable.ArrayBuffer<String> obtainedResult = (scala.collection.mutable
                            .ArrayBuffer<String>)actualRow.get(x);
                    List<String> obtainedResultList = JavaConverters.asJavaListConverter(obtainedResult).asJava();
                    String[] expectedResult = table.get(i + 1).get(x).split(",");
                    if(obtainedResult.size() != expectedResult.length){
                        failWithMessage("Expected length of array to be <%s> but was <%s>", expectedResult.length,
                                obtainedResultList
                                        .size());
                    }
                    for(int j = 0; j < obtainedResult.size(); j++){
                        if(!obtainedResultList.get(j).equals(expectedResult[j])) {
                            failWithMessage("Expected value for row <%s> and position <%s> for column <%s> to be "
                                            + "<%s> but was <%s>",
                                    i,j,
                                    columnExpected[0], expectedResult[j],
                                    obtainedResultList.get(j));
                        }
                    }
                    break;
                default:
                    failWithMessage("The type <%s> is not implemented", columnExpected[1]);
                }
            }
        }
        return this;
    }

   public DataFrameAssert equalsResultsIgnoringOrderNative(List<List<String>> table){
        Row[] actualRows = actual.collect(ExecutionType.Native());
        for(int i = 0; i < actualRows.length; i++) {
            Row actualRow = actualRows[i];
            if(!rowIsContainedInDataTable(table, actualRow)){
                failWithMessage("The row <%s> is not conained in the expected result result", i);
            }
        }
        return this;
    }

    private boolean rowIsContainedInDataTable(List<List<String>> table, Row row){
        boolean isContained = false;
        List<String> firstRow = table.get(0);
        for(int i = 1; i < table.size(); i++){
            List<String> tableRow = table.get(i);
            if( isContained = equalsRows(firstRow,tableRow, row)){
                break;
            }

        }
        return isContained;
    }

    private boolean equalsRows(List<String> firstRow, List<String> tableRow, Row actualRow){
        boolean equals = true;
        for(int i = 0; i < tableRow.size() && equals; i++) {
            String[] columnExpected = firstRow.get(i).split("-");
            switch (columnExpected[1]) {
            case "boolean":
                if (!(actualRow.get(i) instanceof Boolean)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Boolean\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(actualRow.getBoolean(i) != (Boolean.parseBoolean(tableRow.get(i)))){
                     return equals = false;
                }
                break;
            case "byte":
                if (!(actualRow.get(i) instanceof Byte)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Byte\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(actualRow.getByte(i) != Byte.parseByte(tableRow.get(i))){
                    return equals = false;

                }
                break;
            case "date":
                if (!(actualRow.get(i) instanceof java.sql.Date)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.sql.Date\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(!actualRow.getDate(i).equals(Date.valueOf(tableRow.get(i)))){
                    return equals = false;

                }
                break;
            case "decimal":
                if (!(actualRow.get(i) instanceof java.math.BigDecimal)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.math.BigDecimal\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(!actualRow.getDecimal(i).equals(Decimal.apply(tableRow.get(i)))){
                    return equals = false;

                }
                break;
            case "double":
                if (!(actualRow.get(i) instanceof Double)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Double\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(actualRow.getDouble(i) != (Double.parseDouble(tableRow.get(i)))) {
                    return equals = false;

                }
                break;
            case "float":
                if (!(actualRow.get(i) instanceof Float)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Float\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(actualRow.getFloat(i) != (Float.parseFloat(tableRow.get(i)))) {
                    return equals = false;

                }
                break;
            case "integer":
                if (!(actualRow.get(i) instanceof Integer)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Integer\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(actualRow.getInt(i) != Integer.parseInt(tableRow.get(i))){
                    return equals = false;

                }
                break;
            case "long":
                if (!(actualRow.get(i) instanceof Long)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Long\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(actualRow.getLong(i) != Long.parseLong(tableRow.get(i))){
                    return equals = false;

                }
                break;
            case "short":
                if (!(actualRow.get(i) instanceof Short)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.Short\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(actualRow.getShort(i) != Short.parseShort(tableRow.get(i))){
                    return equals = false;

                }
                break;
            case "string":
                if (!(actualRow.get(i) instanceof String)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.lang.String\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(!actualRow.getString(i).equals(tableRow.get(i))){
                    return equals = false;

                }
                break;
            case "timestamp":
                if (!(actualRow.get(i) instanceof java.sql.Timestamp)){
                    failWithMessage("Expected type for row <%s> for column <%s> to be \"java.sql.Timestamp\" "
                                    + "but  was <%s>", i,
                            columnExpected[0], actualRow.get(i).getClass().getName());
                }
                if(!actualRow.getTimestamp(i).equals(Timestamp.valueOf(tableRow.get(i)))){
                    return equals = false;
                }
                break;
            default:
                failWithMessage("The type <%s> is not implemented", columnExpected[1]);
            }
        }
        return equals;
    }
}
