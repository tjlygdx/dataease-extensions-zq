package io.dataease.plugins.datasource.sls.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import io.dataease.plugins.common.base.domain.ChartViewWithBLOBs;
import io.dataease.plugins.common.base.domain.DatasetTableField;
import io.dataease.plugins.common.base.domain.DatasetTableFieldExample;
import io.dataease.plugins.common.base.domain.Datasource;
import io.dataease.plugins.common.base.mapper.DatasetTableFieldMapper;
import io.dataease.plugins.common.constants.DeTypeConstants;
import io.dataease.plugins.common.constants.datasource.MySQLConstants;
import io.dataease.plugins.common.constants.datasource.OracleConstants;
import io.dataease.plugins.common.constants.datasource.SQLConstants;
import io.dataease.plugins.common.dto.chart.ChartCustomFilterItemDTO;
import io.dataease.plugins.common.dto.chart.ChartFieldCustomFilterDTO;
import io.dataease.plugins.common.dto.chart.ChartViewFieldDTO;
import io.dataease.plugins.common.dto.datasource.DeSortField;
import io.dataease.plugins.common.dto.sqlObj.SQLObj;
import io.dataease.plugins.common.request.chart.ChartExtFilterRequest;
import io.dataease.plugins.common.request.permission.DataSetRowPermissionsTreeDTO;
import io.dataease.plugins.common.request.permission.DatasetRowPermissionsTreeItem;
import io.dataease.plugins.datasource.entity.Dateformat;
import io.dataease.plugins.datasource.sls.provider.SlsConfig;
import io.dataease.plugins.datasource.entity.JdbcConfiguration;
import io.dataease.plugins.datasource.query.QueryProvider;
import io.dataease.plugins.datasource.query.Utils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupFile;

import javax.annotation.Resource;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.dataease.plugins.common.constants.datasource.SQLConstants.TABLE_ALIAS_PREFIX;

/**
 * @Author ZhaoQian
 * @Date 2022-12
 */
@Component()
public class SlsQueryProvider extends QueryProvider {

    @Resource
    private DatasetTableFieldMapper datasetTableFieldMapper;

    @Override
    public Integer transFieldType(String field) {
        switch (field) {
            case "text":
            case "json":
                return 0;// 文本
            case "DATE":
            case "TIME":
            case "YEAR":
            case "DATETIME":
            case "TIMESTAMP":
                return 1;// 时间
            case "long":
                return 2;// 整型
            case "double":
                return 3;// 浮点
            case "BOOLEAN":
                return 4;// 布尔
            default:
                return 0;
        }
    }

    @Override
    public String createSQLPreview(String sql, String orderBy) {
        return concatSql("select * from (" + sql + ") limit 1000");
    }

    @Override
    public String createQuerySQL(String table, List<DatasetTableField> fields, boolean isGroup, Datasource ds,
                                 List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<DeSortField> sortFields) {
        SQLObj tableObj = SQLObj.builder()
                .tableName((table.startsWith("(") && table.endsWith(")")) ? table
                        : String.format(SlsConstants.KEYWORD_TABLE, table))
                .tableAlias(String.format(SlsConstants.ALIAS_FIX, String.format(TABLE_ALIAS_PREFIX, 0)))
                .build();

        List<SQLObj> xFields = xFields(table, fields);

        STGroup stg = new STGroupFile(SQLConstants.SQL_TEMPLATE);
        ST st_sql = stg.getInstanceOf("previewSql");
        st_sql.add("isGroup", isGroup);
        if (CollectionUtils.isNotEmpty(xFields))
            st_sql.add("groups", xFields);
        if (ObjectUtils.isNotEmpty(tableObj))
            st_sql.add("table", tableObj);
        String customWheres = transCustomFilterList(tableObj, fieldCustomFilter);
        // row permissions tree
        String whereTrees = transFilterTrees(tableObj, rowPermissionsTree);
        List<String> wheres = new ArrayList<>();
        if (customWheres != null)
            wheres.add(customWheres);
        if (whereTrees != null)
            wheres.add(whereTrees);
        if (CollectionUtils.isNotEmpty(wheres))
            st_sql.add("filters", wheres);

        List<SQLObj> xOrders = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(sortFields)) {
            int step = fields.size();
            for (int i = step; i < (step + sortFields.size()); i++) {
                DeSortField deSortField = sortFields.get(i - step);
                SQLObj order = buildSortField(deSortField, tableObj, i);
                xOrders.add(order);
            }
        }
        if (ObjectUtils.isNotEmpty(xOrders)) {
            st_sql.add("orders", xOrders);
        }

        return concatSql(st_sql.render().replace("\n", "  "));
    }

    @Override
    public String createQuerySQL(String table, List<DatasetTableField> fields, boolean isGroup, Datasource ds,
                                 List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree) {
        return concatSql(createQuerySQL(table, fields, isGroup, ds, fieldCustomFilter, rowPermissionsTree, null));
    }

    @Override
    public String createQuerySQLAsTmp(String sql, List<DatasetTableField> fields, boolean isGroup,
                                      List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<DeSortField> sortFields) {
        return concatSql(createQuerySQL("(" + sqlFix(sql) + ")", fields, isGroup, null, fieldCustomFilter, rowPermissionsTree, sortFields));
    }

    private SQLObj buildSortField(DeSortField f, SQLObj tableObj, int index) {
        String originField;
        if (ObjectUtils.isNotEmpty(f.getExtField()) && f.getExtField() == 2) {
            // 解析origin name中有关联的字段生成sql表达式
            originField = calcFieldRegex(f.getOriginName(), tableObj);
        } else if (ObjectUtils.isNotEmpty(f.getExtField()) && f.getExtField() == 1) {
            originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(), f.getOriginName());
        } else {
            originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(), f.getOriginName());
        }
//        String fieldAlias = String.format(SlsConstants.ALIAS_FIX,
//                String.format(SQLConstants.FIELD_ALIAS_X_PREFIX, index));
//        String fieldName = "";
//        // 处理横轴字段
//        if (f.getDeExtractType() == 1) {
//            if (f.getDeType() == 2 || f.getDeType() == 3) {
//                fieldName = String.format(SlsConstants.UNIX_TIMESTAMP, originField) + "*1000";
//            } else {
//                fieldName = originField;
//            }
//        } else if (f.getDeExtractType() == 0) {
//            if (f.getDeType() == 2) {
//                fieldName = String.format(SlsConstants.CAST, originField, SlsConstants.DEFAULT_INT_FORMAT);
//            } else if (f.getDeType() == 3) {
//                fieldName = String.format(SlsConstants.CAST, originField, SlsConstants.DEFAULT_FLOAT_FORMAT);
//            } else if (f.getDeType() == 1) {
//                fieldName = String.format(SlsConstants.DATE_FORMAT, originField, SlsConstants.DEFAULT_DATE_FORMAT);
//            } else {
//                fieldName = originField;
//            }
//        } else {
//            if (f.getDeType() == 1) {
//                String cast = String.format(SlsConstants.CAST, originField, SlsConstants.DEFAULT_INT_FORMAT)
//                        + "/1000";
//                fieldName = String.format(SlsConstants.FROM_UNIXTIME, cast, SlsConstants.DEFAULT_DATE_FORMAT);
//            } else if (f.getDeType() == 2) {
//                fieldName = String.format(SlsConstants.CAST, originField, SlsConstants.DEFAULT_INT_FORMAT);
//            } else {
//                fieldName = originField;
//            }
//        }
        SQLObj result = SQLObj.builder()
                .orderField(originField)
                .orderAlias(originField)
                .orderDirection(f.getOrderDirection()).build();
        return result;
    }

    private List<SQLObj> xFields(String table, List<DatasetTableField> fields) {
        SQLObj tableObj = SQLObj.builder()
                .tableName((table.startsWith("(") && table.endsWith(")")) ? table
                        : String.format(SlsConstants.KEYWORD_TABLE, table))
                .tableAlias(String.format(SlsConstants.ALIAS_FIX, String.format(TABLE_ALIAS_PREFIX, 0)))
                .build();
        List<SQLObj> xFields = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(fields)) {
            for (int i = 0; i < fields.size(); i++) {
                DatasetTableField f = fields.get(i);
                String originField;
                if (ObjectUtils.isNotEmpty(f.getExtField()) && f.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originField = calcFieldRegex(f.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(f.getExtField()) && f.getExtField() == 1) {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            f.getOriginName());
                } else {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            f.getOriginName());
                }
                String fieldAlias = String.format(MySQLConstants.ALIAS_FIX, String.format(SQLConstants.FIELD_ALIAS_X_PREFIX, i));
                if (StringUtils.equals(fieldAlias, "__time__")) {
                    fieldAlias = String.format(OracleConstants.ALIAS_FIX, String.format(SQLConstants.FIELD_ALIAS_X_PREFIX, i));
                }
                String fieldName = "";
                // 处理横轴字段
                if (f.getDeExtractType() == 1) {
                    if (f.getDeType() == 2 || f.getDeType() == 3) {
                        fieldName = String.format(SlsConstants.UNIX_TIMESTAMP, originField) + "*1000";
                    } else {
                        fieldName = originField;
                    }
                } else if (f.getDeExtractType() == 0) {
                    if (f.getDeType() == 2) {
                        fieldName = String.format(SlsConstants.CAST, originField,
                                SlsConstants.DEFAULT_INT_FORMAT);
                    } else if (f.getDeType() == 3) {
                        fieldName = String.format(SlsConstants.CAST, originField,
                                SlsConstants.DEFAULT_FLOAT_FORMAT);
                    } else if (f.getDeType() == 1) {
                        fieldName = String.format(SlsConstants.DATE_FORMAT, originField,
                                SlsConstants.DEFAULT_DATE_FORMAT);
                    } else {
                        fieldName = originField;
                    }
                } else {
                    if (f.getDeType() == 1) {
                        String cast = String.format(SlsConstants.CAST, originField,
                                SlsConstants.DEFAULT_INT_FORMAT) + "/1000";
                        fieldName = String.format(SlsConstants.FROM_UNIXTIME, cast,
                                SlsConstants.DEFAULT_DATE_FORMAT);
                    } else if (f.getDeType() == 2) {
                        fieldName = String.format(SlsConstants.CAST, originField,
                                SlsConstants.DEFAULT_INT_FORMAT);
                    } else {
                        fieldName = originField;
                    }
                }
                xFields.add(SQLObj.builder()
                        .fieldName(fieldName)
                        .fieldAlias(fieldAlias)
                        .build());
            }
        }
        return xFields;
    }

    private String sqlColumn(List<SQLObj> xFields) {
        String[] array = xFields.stream().map(f -> {
            return f.getFieldAlias();
        }).toArray(String[]::new);
        return StringUtils.join(array, ",");
    }

    @Override
    public String createQuerySQLAsTmp(String sql, List<DatasetTableField> fields, boolean isGroup,
                                      List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree) {
        return concatSql(createQuerySQL("(" + sqlFix(sql) + ")", fields, isGroup, null, fieldCustomFilter, rowPermissionsTree));
    }

    @Override
    public String createQueryTableWithPage(String table, List<DatasetTableField> fields, Integer page, Integer pageSize,
                                           Integer realSize, boolean isGroup, Datasource ds, List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree) {
        List<SQLObj> xFields = xFields(table, fields);

        return concatSql(MessageFormat.format("SELECT {0} FROM ( SELECT DE_TMP.* FROM ({1}) DE_TMP) limit ",
                sqlColumn(xFields), createQuerySQL(table, fields, isGroup, ds, fieldCustomFilter, rowPermissionsTree))) + page + "," + realSize;
    }

    @Override
    public String createQuerySQLWithPage(String sql, List<DatasetTableField> fields, Integer page, Integer pageSize,
                                         Integer realSize, boolean isGroup, List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree) {
        List<SQLObj> xFields = xFields("(" + sqlFix(sql) + ")", fields);
        return concatSql(MessageFormat.format(
                "SELECT {0} FROM ( SELECT DE_TMP.* FROM ( {1} ) DE_TMP) limit ",
                sqlColumn(xFields), createQuerySQLAsTmp(sql, fields, isGroup, fieldCustomFilter, rowPermissionsTree))) + String.valueOf(page-1) + "," + realSize;
    }

    @Override
    public String createQueryTableWithLimit(String table, List<DatasetTableField> fields, Integer limit,
                                            boolean isGroup, Datasource ds, List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree) {
        return concatSql(String.format("SELECT *  from %s  limit %s ", String.format(SlsConstants.KEYWORD_TABLE, table), limit.toString()));
    }

    @Override
    public String createQuerySqlWithLimit(String sql, List<DatasetTableField> fields, Integer limit, boolean isGroup,
                                          List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree) {
        return concatSql(String.format("SELECT * from %s  limit %s ", "(" + sqlFix(sql) + ")", limit.toString()));
    }

    @Override
    public String getSQL(String table, List<ChartViewFieldDTO> xAxis, List<ChartViewFieldDTO> yAxis,
                         List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                         Datasource ds, ChartViewWithBLOBs view) {
        SQLObj tableObj = SQLObj.builder()
                .tableName((table.startsWith("(") && table.endsWith(")")) ? table
                        : String.format(SlsConstants.KEYWORD_TABLE, table))
                .tableAlias(String.format(SlsConstants.ALIAS_FIX, String.format(TABLE_ALIAS_PREFIX, 0)))
                .build();
        List<SQLObj> xFields = new ArrayList<>();
        List<SQLObj> xOrders = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(xAxis)) {
            for (int i = 0; i < xAxis.size(); i++) {
                ChartViewFieldDTO x = xAxis.get(i);
                String originField;
                if (ObjectUtils.isNotEmpty(x.getExtField()) && x.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originField = calcFieldRegex(x.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(x.getExtField()) && x.getExtField() == 1) {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            x.getOriginName());
                } else {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            x.getOriginName());
                }
                String fieldAlias = String.format(SlsConstants.ALIAS_FIX,
                        String.format(SQLConstants.FIELD_ALIAS_X_PREFIX, i));
                // 处理横轴字段
                xFields.add(getXFields(x, originField, fieldAlias));
                // 处理横轴排序
                if (StringUtils.isNotEmpty(x.getSort()) && Utils.joinSort(x.getSort())) {
                    xOrders.add(SQLObj.builder()
                            .orderField(originField)
                            .orderAlias(fieldAlias)
                            .orderDirection(x.getSort())
                            .build());
                }
            }
        }
        List<SQLObj> yFields = new ArrayList<>();
        List<String> yWheres = new ArrayList<>();
        List<SQLObj> yOrders = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(yAxis)) {
            for (int i = 0; i < yAxis.size(); i++) {
                ChartViewFieldDTO y = yAxis.get(i);
                String originField;
                if (ObjectUtils.isNotEmpty(y.getExtField()) && y.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originField = calcFieldRegex(y.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(y.getExtField()) && y.getExtField() == 1) {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            y.getOriginName());
                } else {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            y.getOriginName());
                }
                String fieldAlias = String.format(SlsConstants.ALIAS_FIX,
                        String.format(SQLConstants.FIELD_ALIAS_Y_PREFIX, i));
                // 处理纵轴字段
                yFields.add(getYFields(y, originField, fieldAlias));
                // 处理纵轴过滤
                yWheres.add(getYWheres(y, originField, fieldAlias));
                // 处理纵轴排序
                if (StringUtils.isNotEmpty(y.getSort()) && Utils.joinSort(y.getSort())) {
                    yOrders.add(SQLObj.builder()
                            .orderField(originField)
                            .orderAlias(fieldAlias)
                            .orderDirection(y.getSort())
                            .build());
                }
            }
        }
        // 处理视图中字段过滤
        String customWheres = transCustomFilterList(tableObj, fieldCustomFilter);
        // 处理仪表板字段过滤
        String extWheres = transExtFilterList(tableObj, extFilterRequestList);
        // row permissions tree
        String whereTrees = transFilterTrees(tableObj, rowPermissionsTree);
        // 构建sql所有参数
        List<SQLObj> fields = new ArrayList<>();
        fields.addAll(xFields);
        fields.addAll(yFields);
        List<String> wheres = new ArrayList<>();
        if (customWheres != null)
            wheres.add(customWheres);
        if (extWheres != null)
            wheres.add(extWheres);
        if (whereTrees != null)
            wheres.add(whereTrees);
        List<SQLObj> groups = new ArrayList<>();
        groups.addAll(xFields);
        // 外层再次套sql
        List<SQLObj> orders = new ArrayList<>();
        orders.addAll(xOrders);
        orders.addAll(yOrders);
        List<String> aggWheres = new ArrayList<>();
        aggWheres.addAll(yWheres.stream().filter(ObjectUtils::isNotEmpty).collect(Collectors.toList()));

        STGroup stg = new STGroupFile(SQLConstants.SQL_TEMPLATE);
        ST st_sql = stg.getInstanceOf("querySql");
        if (CollectionUtils.isNotEmpty(xFields))
            st_sql.add("groups", xFields);
        if (CollectionUtils.isNotEmpty(yFields))
            st_sql.add("aggregators", yFields);
        if (CollectionUtils.isNotEmpty(wheres))
            st_sql.add("filters", wheres);
        if (ObjectUtils.isNotEmpty(tableObj))
            st_sql.add("table", tableObj);
        String sql = st_sql.render().replace("\n","  ");

        ST st = stg.getInstanceOf("querySql");
        SQLObj tableSQL = SQLObj.builder()
                .tableName(String.format(SlsConstants.BRACKETS, sql))
                .tableAlias(String.format(TABLE_ALIAS_PREFIX, 1))
                .build();
        if (CollectionUtils.isNotEmpty(aggWheres))
            st.add("filters", aggWheres);
        if (CollectionUtils.isNotEmpty(orders))
            st.add("orders", orders);
        if (ObjectUtils.isNotEmpty(tableSQL))
            st.add("table", tableSQL);
        return sqlLimit(st.render(), view);
    }

    @Override
    public String getSQLTableInfo(String table, List<ChartViewFieldDTO> xAxis,
                                  List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                                  Datasource ds, ChartViewWithBLOBs view) {
        SQLObj tableObj = SQLObj.builder()
                .tableName((table.startsWith("(") && table.endsWith(")")) ? table
                        : String.format(SlsConstants.KEYWORD_TABLE, table))
                .tableAlias(String.format(SlsConstants.ALIAS_FIX, String.format(TABLE_ALIAS_PREFIX, 0)))
                .build();
        List<SQLObj> xFields = new ArrayList<>();
        List<SQLObj> xOrders = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(xAxis)) {
            for (int i = 0; i < xAxis.size(); i++) {
                ChartViewFieldDTO x = xAxis.get(i);
                String originField;
                if (ObjectUtils.isNotEmpty(x.getExtField()) && x.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originField = calcFieldRegex(x.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(x.getExtField()) && x.getExtField() == 1) {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            x.getOriginName());
                } else {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            x.getOriginName());
                }
                String fieldAlias = String.format(SlsConstants.ALIAS_FIX,
                        String.format(SQLConstants.FIELD_ALIAS_X_PREFIX, i));
                // 处理横轴字段
                xFields.add(getXFields(x, originField, fieldAlias));
                // 处理横轴排序
                if (StringUtils.isNotEmpty(x.getSort()) && Utils.joinSort(x.getSort())) {
                    xOrders.add(SQLObj.builder()
                            .orderField(originField)
                            .orderAlias(fieldAlias)
                            .orderDirection(x.getSort())
                            .build());
                }
            }
        }
        // 处理视图中字段过滤
        String customWheres = transCustomFilterList(tableObj, fieldCustomFilter);
        // 处理仪表板字段过滤
        String extWheres = transExtFilterList(tableObj, extFilterRequestList);
        // row permissions tree
        String whereTrees = transFilterTrees(tableObj, rowPermissionsTree);
        // 构建sql所有参数
        List<SQLObj> fields = new ArrayList<>();
        fields.addAll(xFields);
        List<String> wheres = new ArrayList<>();
        if (customWheres != null)
            wheres.add(customWheres);
        if (extWheres != null)
            wheres.add(extWheres);
        if (whereTrees != null)
            wheres.add(whereTrees);
        List<SQLObj> groups = new ArrayList<>();
        groups.addAll(xFields);
        // 外层再次套sql
        List<SQLObj> orders = new ArrayList<>();
        orders.addAll(xOrders);

        STGroup stg = new STGroupFile(SQLConstants.SQL_TEMPLATE);
        ST st_sql = stg.getInstanceOf("previewSql");
        st_sql.add("isGroup", false);
        if (CollectionUtils.isNotEmpty(xFields))
            st_sql.add("groups", xFields);
        if (CollectionUtils.isNotEmpty(wheres))
            st_sql.add("filters", wheres);
        if (ObjectUtils.isNotEmpty(tableObj))
            st_sql.add("table", tableObj);
        if (CollectionUtils.isNotEmpty(orders))
            st_sql.add("orders", orders);
        String sql = st_sql.render();

        return String.format(SlsConstants.SQL_FIX, replaceSql(sqlLimit(sql, view)));
    }

    @Override
    public String getSQLAsTmpTableInfo(String sql, List<ChartViewFieldDTO> xAxis,
                                       List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                                       Datasource ds, ChartViewWithBLOBs view) {
        return concatSql(getSQLTableInfo("(" + sqlFix(sql) + ")", xAxis, fieldCustomFilter, rowPermissionsTree, extFilterRequestList, null, view));
    }

    @Override
    public String getSQLAsTmp(String sql, List<ChartViewFieldDTO> xAxis, List<ChartViewFieldDTO> yAxis,
                              List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                              ChartViewWithBLOBs view) {
        return concatSql(getSQL("(" + sqlFix(sql) + ")", xAxis, yAxis, fieldCustomFilter, rowPermissionsTree, extFilterRequestList, null, view));
    }

    @Override
    public String getSQLStack(String table, List<ChartViewFieldDTO> xAxis, List<ChartViewFieldDTO> yAxis,
                              List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                              List<ChartViewFieldDTO> extStack, Datasource ds, ChartViewWithBLOBs view) {
        SQLObj tableObj = SQLObj.builder()
                .tableName((table.startsWith("(") && table.endsWith(")")) ? table
                        : String.format(SlsConstants.KEYWORD_TABLE, table))
                .tableAlias(String.format(SlsConstants.ALIAS_FIX, String.format(TABLE_ALIAS_PREFIX, 0)))
                .build();
        List<SQLObj> xFields = new ArrayList<>();
        List<SQLObj> xOrders = new ArrayList<>();
        List<ChartViewFieldDTO> xList = new ArrayList<>();
        xList.addAll(xAxis);
        xList.addAll(extStack);
        if (CollectionUtils.isNotEmpty(xList)) {
            for (int i = 0; i < xList.size(); i++) {
                ChartViewFieldDTO x = xList.get(i);
                String originField;
                if (ObjectUtils.isNotEmpty(x.getExtField()) && x.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originField = calcFieldRegex(x.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(x.getExtField()) && x.getExtField() == 1) {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            x.getOriginName());
                } else {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            x.getOriginName());
                }
                String fieldAlias = String.format(SlsConstants.ALIAS_FIX,
                        String.format(SQLConstants.FIELD_ALIAS_X_PREFIX, i));
                // 处理横轴字段
                xFields.add(getXFields(x, originField, fieldAlias));
                // 处理横轴排序
                if (StringUtils.isNotEmpty(x.getSort()) && Utils.joinSort(x.getSort())) {
                    xOrders.add(SQLObj.builder()
                            .orderField(originField)
                            .orderAlias(fieldAlias)
                            .orderDirection(x.getSort())
                            .build());
                }
            }
        }
        List<SQLObj> yFields = new ArrayList<>();
        List<String> yWheres = new ArrayList<>();
        List<SQLObj> yOrders = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(yAxis)) {
            for (int i = 0; i < yAxis.size(); i++) {
                ChartViewFieldDTO y = yAxis.get(i);
                String originField;
                if (ObjectUtils.isNotEmpty(y.getExtField()) && y.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originField = calcFieldRegex(y.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(y.getExtField()) && y.getExtField() == 1) {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            y.getOriginName());
                } else {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            y.getOriginName());
                }
                String fieldAlias = String.format(SlsConstants.ALIAS_FIX,
                        String.format(SQLConstants.FIELD_ALIAS_Y_PREFIX, i));
                // 处理纵轴字段
                yFields.add(getYFields(y, originField, fieldAlias));
                // 处理纵轴过滤
                yWheres.add(getYWheres(y, originField, fieldAlias));
                // 处理纵轴排序
                if (StringUtils.isNotEmpty(y.getSort()) && Utils.joinSort(y.getSort())) {
                    yOrders.add(SQLObj.builder()
                            .orderField(originField)
                            .orderAlias(fieldAlias)
                            .orderDirection(y.getSort())
                            .build());
                }
            }
        }
        // 处理视图中字段过滤
        String customWheres = transCustomFilterList(tableObj, fieldCustomFilter);
        // 处理仪表板字段过滤
        String extWheres = transExtFilterList(tableObj, extFilterRequestList);
        // row permissions tree
        String whereTrees = transFilterTrees(tableObj, rowPermissionsTree);
        // 构建sql所有参数
        List<SQLObj> fields = new ArrayList<>();
        fields.addAll(xFields);
        fields.addAll(yFields);
        List<String> wheres = new ArrayList<>();
        if (customWheres != null)
            wheres.add(customWheres);
        if (extWheres != null)
            wheres.add(extWheres);
        if (whereTrees != null)
            wheres.add(whereTrees);
        List<SQLObj> groups = new ArrayList<>();
        groups.addAll(xFields);
        // 外层再次套sql
        List<SQLObj> orders = new ArrayList<>();
        orders.addAll(xOrders);
        orders.addAll(yOrders);
        List<String> aggWheres = new ArrayList<>();
        aggWheres.addAll(yWheres.stream().filter(ObjectUtils::isNotEmpty).collect(Collectors.toList()));

        STGroup stg = new STGroupFile(SQLConstants.SQL_TEMPLATE);
        ST st_sql = stg.getInstanceOf("querySql");
        if (CollectionUtils.isNotEmpty(xFields))
            st_sql.add("groups", xFields);
        if (CollectionUtils.isNotEmpty(yFields))
            st_sql.add("aggregators", yFields);
        if (CollectionUtils.isNotEmpty(wheres))
            st_sql.add("filters", wheres);
        if (ObjectUtils.isNotEmpty(tableObj))
            st_sql.add("table", tableObj);
        String sql = st_sql.render().replace("\n","  ");

        ST st = stg.getInstanceOf("querySql");
        SQLObj tableSQL = SQLObj.builder()
                .tableName(String.format(SlsConstants.BRACKETS, sql))
                .tableAlias(String.format(TABLE_ALIAS_PREFIX, 1))
                .build();
        if (CollectionUtils.isNotEmpty(aggWheres))
            st.add("filters", aggWheres);
        if (CollectionUtils.isNotEmpty(orders))
            st.add("orders", orders);
        if (ObjectUtils.isNotEmpty(tableSQL))
            st.add("table", tableSQL);
        return sqlLimit(st.render(), view);
    }

    @Override
    public String getSQLAsTmpStack(String table, List<ChartViewFieldDTO> xAxis, List<ChartViewFieldDTO> yAxis,
                                   List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                                   List<ChartViewFieldDTO> extStack, ChartViewWithBLOBs view) {
        return concatSql(getSQLStack("(" + sqlFix(table) + ")", xAxis, yAxis, fieldCustomFilter, rowPermissionsTree, extFilterRequestList, extStack,
                null, view));
    }

    @Override
    public String getSQLScatter(String table, List<ChartViewFieldDTO> xAxis, List<ChartViewFieldDTO> yAxis,
                                List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                                List<ChartViewFieldDTO> extBubble, Datasource ds, ChartViewWithBLOBs view) {
        SQLObj tableObj = SQLObj.builder()
                .tableName((table.startsWith("(") && table.endsWith(")")) ? table
                        : String.format(SlsConstants.KEYWORD_TABLE, table))
                .tableAlias(String.format(SlsConstants.ALIAS_FIX, String.format(TABLE_ALIAS_PREFIX, 0)))
                .build();
        List<SQLObj> xFields = new ArrayList<>();
        List<SQLObj> xOrders = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(xAxis)) {
            for (int i = 0; i < xAxis.size(); i++) {
                ChartViewFieldDTO x = xAxis.get(i);
                String originField;
                if (ObjectUtils.isNotEmpty(x.getExtField()) && x.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originField = calcFieldRegex(x.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(x.getExtField()) && x.getExtField() == 1) {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            x.getOriginName());
                } else {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            x.getOriginName());
                }
                String fieldAlias = String.format(SlsConstants.ALIAS_FIX,
                        String.format(SQLConstants.FIELD_ALIAS_X_PREFIX, i));
                // 处理横轴字段
                xFields.add(getXFields(x, originField, fieldAlias));
                // 处理横轴排序
                if (StringUtils.isNotEmpty(x.getSort()) && Utils.joinSort(x.getSort())) {
                    xOrders.add(SQLObj.builder()
                            .orderField(originField)
                            .orderAlias(fieldAlias)
                            .orderDirection(x.getSort())
                            .build());
                }
            }
        }
        List<SQLObj> yFields = new ArrayList<>();
        List<String> yWheres = new ArrayList<>();
        List<SQLObj> yOrders = new ArrayList<>();
        List<ChartViewFieldDTO> yList = new ArrayList<>();
        yList.addAll(yAxis);
        yList.addAll(extBubble);
        if (CollectionUtils.isNotEmpty(yList)) {
            for (int i = 0; i < yList.size(); i++) {
                ChartViewFieldDTO y = yList.get(i);
                String originField;
                if (ObjectUtils.isNotEmpty(y.getExtField()) && y.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originField = calcFieldRegex(y.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(y.getExtField()) && y.getExtField() == 1) {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            y.getOriginName());
                } else {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            y.getOriginName());
                }
                String fieldAlias = String.format(SlsConstants.ALIAS_FIX,
                        String.format(SQLConstants.FIELD_ALIAS_Y_PREFIX, i));
                // 处理纵轴字段
                yFields.add(getYFields(y, originField, fieldAlias));
                // 处理纵轴过滤
                yWheres.add(getYWheres(y, originField, fieldAlias));
                // 处理纵轴排序
                if (StringUtils.isNotEmpty(y.getSort()) && Utils.joinSort(y.getSort())) {
                    yOrders.add(SQLObj.builder()
                            .orderField(originField)
                            .orderAlias(fieldAlias)
                            .orderDirection(y.getSort())
                            .build());
                }
            }
        }
        // 处理视图中字段过滤
        String customWheres = transCustomFilterList(tableObj, fieldCustomFilter);
        // 处理仪表板字段过滤
        String extWheres = transExtFilterList(tableObj, extFilterRequestList);
        // row permissions tree
        String whereTrees = transFilterTrees(tableObj, rowPermissionsTree);
        // 构建sql所有参数
        List<SQLObj> fields = new ArrayList<>();
        fields.addAll(xFields);
        fields.addAll(yFields);
        List<String> wheres = new ArrayList<>();
        if (customWheres != null)
            wheres.add(customWheres);
        if (extWheres != null)
            wheres.add(extWheres);
        if (whereTrees != null)
            wheres.add(whereTrees);
        List<SQLObj> groups = new ArrayList<>();
        groups.addAll(xFields);
        // 外层再次套sql
        List<SQLObj> orders = new ArrayList<>();
        orders.addAll(xOrders);
        orders.addAll(yOrders);
        List<String> aggWheres = new ArrayList<>();
        aggWheres.addAll(yWheres.stream().filter(ObjectUtils::isNotEmpty).collect(Collectors.toList()));

        STGroup stg = new STGroupFile(SQLConstants.SQL_TEMPLATE);
        ST st_sql = stg.getInstanceOf("querySql");
        if (CollectionUtils.isNotEmpty(xFields))
            st_sql.add("groups", xFields);
        if (CollectionUtils.isNotEmpty(yFields))
            st_sql.add("aggregators", yFields);
        if (CollectionUtils.isNotEmpty(wheres))
            st_sql.add("filters", wheres);
        if (ObjectUtils.isNotEmpty(tableObj))
            st_sql.add("table", tableObj);
        String sql = st_sql.render().replace("\n","  ");

        ST st = stg.getInstanceOf("querySql");
        SQLObj tableSQL = SQLObj.builder()
                .tableName(String.format(SlsConstants.BRACKETS, sql))
                .tableAlias(String.format(TABLE_ALIAS_PREFIX, 1))
                .build();
        if (CollectionUtils.isNotEmpty(aggWheres))
            st.add("filters", aggWheres);
        if (CollectionUtils.isNotEmpty(orders))
            st.add("orders", orders);
        if (ObjectUtils.isNotEmpty(tableSQL))
            st.add("table", tableSQL);
        return concatSql(sqlLimit(st.render(), view));
    }

    @Override
    public String getSQLAsTmpScatter(String table, List<ChartViewFieldDTO> xAxis, List<ChartViewFieldDTO> yAxis,
                                     List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                                     List<ChartViewFieldDTO> extBubble, ChartViewWithBLOBs view) {
        return concatSql((getSQLScatter("(" + sqlFix(table) + ")", xAxis, yAxis, fieldCustomFilter, rowPermissionsTree, extFilterRequestList,
                extBubble, null, view)));
    }

    @Override
    public String searchTable(String table) {
        return concatSql("SELECT table_name FROM information_schema.TABLES WHERE table_name ='" + table + "'");
    }

    @Override
    public String getSQLSummary(String table, List<ChartViewFieldDTO> yAxis,
                                List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                                ChartViewWithBLOBs view, Datasource ds) {
        // 字段汇总 排序等
        SQLObj tableObj = SQLObj.builder()
                .tableName((table.startsWith("(") && table.endsWith(")")) ? table
                        : String.format(SlsConstants.KEYWORD_TABLE, table))
                .tableAlias(String.format(SlsConstants.ALIAS_FIX, String.format(TABLE_ALIAS_PREFIX, 0)))
                .build();
        List<SQLObj> yFields = new ArrayList<>();
        List<String> yWheres = new ArrayList<>();
        List<SQLObj> yOrders = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(yAxis)) {
            for (int i = 0; i < yAxis.size(); i++) {
                ChartViewFieldDTO y = yAxis.get(i);
                String originField;
                if (ObjectUtils.isNotEmpty(y.getExtField()) && y.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originField = calcFieldRegex(y.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(y.getExtField()) && y.getExtField() == 1) {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            y.getOriginName());
                } else {
                    originField = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            y.getOriginName());
                }
                String fieldAlias = String.format(SQLConstants.FIELD_ALIAS_Y_PREFIX, i);
                // 处理纵轴字段
                yFields.add(getYFields(y, originField, fieldAlias));
                // 处理纵轴过滤
                yWheres.add(getYWheres(y, originField, fieldAlias));
                // 处理纵轴排序
                if (StringUtils.isNotEmpty(y.getSort()) && Utils.joinSort(y.getSort())) {
                    yOrders.add(SQLObj.builder()
                            .orderField(originField)
                            .orderAlias(fieldAlias)
                            .orderDirection(y.getSort())
                            .build());
                }
            }
        }
        // 处理视图中字段过滤
        String customWheres = transCustomFilterList(tableObj, fieldCustomFilter);
        // 处理仪表板字段过滤
        String extWheres = transExtFilterList(tableObj, extFilterRequestList);
        // row permissions tree
        String whereTrees = transFilterTrees(tableObj, rowPermissionsTree);
        // 构建sql所有参数
        List<SQLObj> fields = new ArrayList<>();
        fields.addAll(yFields);
        List<String> wheres = new ArrayList<>();
        if (customWheres != null)
            wheres.add(customWheres);
        if (extWheres != null)
            wheres.add(extWheres);
        if (whereTrees != null)
            wheres.add(whereTrees);
        // 外层再次套sql
        List<SQLObj> orders = new ArrayList<>();
        orders.addAll(yOrders);
        List<String> aggWheres = new ArrayList<>();
        aggWheres.addAll(yWheres.stream().filter(ObjectUtils::isNotEmpty).collect(Collectors.toList()));

        STGroup stg = new STGroupFile(SQLConstants.SQL_TEMPLATE);
        ST st_sql = stg.getInstanceOf("querySql");
        if (CollectionUtils.isNotEmpty(yFields))
            st_sql.add("aggregators", yFields);
        if (CollectionUtils.isNotEmpty(wheres))
            st_sql.add("filters", wheres);
        if (ObjectUtils.isNotEmpty(tableObj))
            st_sql.add("table", tableObj);
        String sql = st_sql.render().replace("\n","  ");

        ST st = stg.getInstanceOf("querySql");
        SQLObj tableSQL = SQLObj.builder()
                .tableName(String.format(SlsConstants.BRACKETS, sql))
                .tableAlias(String.format(TABLE_ALIAS_PREFIX, 1))
                .build();
        if (CollectionUtils.isNotEmpty(aggWheres))
            st.add("filters", aggWheres);
        if (CollectionUtils.isNotEmpty(orders))
            st.add("orders", orders);
        if (ObjectUtils.isNotEmpty(tableSQL))
            st.add("table", tableSQL);
        return concatSql(sqlLimit(st.render(), view));
    }

    @Override
    public String getSQLSummaryAsTmp(String sql, List<ChartViewFieldDTO> yAxis,
                                     List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                                     ChartViewWithBLOBs view) {
        return concatSql(getSQLSummary("(" + sqlFix(sql) + ")", yAxis, fieldCustomFilter, rowPermissionsTree, extFilterRequestList, view, null));
    }

    @Override
    public String wrapSql(String sql) {
        sql = sql.trim();
        if (sql.lastIndexOf(";") == (sql.length() - 1)) {
            sql = sql.substring(0, sql.length() - 1);
        }
        String tmpSql = "SELECT * FROM (" + sql + ") DE_TMP " + " where rownum <= 0";
        return concatSql(tmpSql);
    }

    @Override
    public String createRawQuerySQL(String table, List<DatasetTableField> fields, Datasource ds) {
        String[] array = fields.stream().map(f -> {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(" \"").append(f.getOriginName()).append("\"");
            return stringBuilder.toString();
        }).toArray(String[]::new);
        SlsConfig dmConfig = new Gson().fromJson(ds.getConfiguration(), SlsConfig.class);
        return concatSql(MessageFormat.format("SELECT {0} FROM {1}", StringUtils.join(array, ","),
                dmConfig.getSchema() + ".\"" + table + "\""));
    }

    @Override
    public String createRawQuerySQLAsTmp(String sql, List<DatasetTableField> fields) {
        String[] array = fields.stream().map(f -> {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(" \"").append(f.getOriginName()).append("\"");
            return stringBuilder.toString();
        }).toArray(String[]::new);
        return concatSql(MessageFormat.format("SELECT {0} FROM {1}", StringUtils.join(array, ","),
                " (" + sqlFix(sql) + ") DE_TMP "));
    }

    @Override
    public String transTreeItem(SQLObj tableObj, DatasetRowPermissionsTreeItem item) {
        String res = null;
        DatasetTableField field = item.getField();
        if (ObjectUtils.isEmpty(field)) {
            return null;
        }
        String whereName = "";
        String originName;
        if (ObjectUtils.isNotEmpty(field.getExtField()) && field.getExtField() == 2) {
            // 解析origin name中有关联的字段生成sql表达式
            originName = calcFieldRegex(field.getOriginName(), tableObj);
        } else if (ObjectUtils.isNotEmpty(field.getExtField()) && field.getExtField() == 1) {
            originName = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                    field.getOriginName());
        } else {
            originName = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                    field.getOriginName());
        }

        if (field.getDeType() == 1) {
            if (field.getDeExtractType() == 0 || field.getDeExtractType() == 5) {
                whereName = String.format(SlsConstants.STR_TO_DATE, originName, StringUtils.isNotEmpty(field.getDateFormat()) ? field.getDateFormat() : SlsConstants.DEFAULT_DATE_FORMAT);
            }
            if (field.getDeExtractType() == 2 || field.getDeExtractType() == 3 || field.getDeExtractType() == 4) {
                String cast = String.format(SlsConstants.CAST, originName, SlsConstants.DEFAULT_INT_FORMAT)
                        + "/1000";
                whereName = String.format(SlsConstants.FROM_UNIXTIME, cast, SlsConstants.DEFAULT_DATE_FORMAT);
            }
            if (field.getDeExtractType() == 1) {
                whereName = originName;
            }
        } else if (field.getDeType() == 2 || field.getDeType() == 3) {
            if (field.getDeExtractType() == 0 || field.getDeExtractType() == 5) {
                whereName = String.format(SlsConstants.CAST, originName, SlsConstants.DEFAULT_FLOAT_FORMAT);
            }
            if (field.getDeExtractType() == 1) {
                whereName = String.format(SlsConstants.UNIX_TIMESTAMP, originName) + "*1000";
            }
            if (field.getDeExtractType() == 2 || field.getDeExtractType() == 3 || field.getDeExtractType() == 4) {
                whereName = originName;
            }
        } else {
            whereName = originName;
        }

        if (StringUtils.equalsIgnoreCase(item.getFilterType(), "enum")) {
            if (CollectionUtils.isNotEmpty(item.getEnumValue())) {
                res = "(" + whereName + " IN ('" + String.join("','", item.getEnumValue()) + "'))";
            }
        } else {
            String value = item.getValue();
            String whereTerm = transMysqlFilterTerm(item.getTerm());
            String whereValue = "";

            if (StringUtils.equalsIgnoreCase(item.getTerm(), "null")) {
                whereValue = "";
            } else if (StringUtils.equalsIgnoreCase(item.getTerm(), "not_null")) {
                whereValue = "";
            } else if (StringUtils.equalsIgnoreCase(item.getTerm(), "empty")) {
                whereValue = "''";
            } else if (StringUtils.equalsIgnoreCase(item.getTerm(), "not_empty")) {
                whereValue = "''";
            } else if (StringUtils.containsIgnoreCase(item.getTerm(), "in")
                    || StringUtils.containsIgnoreCase(item.getTerm(), "not in")) {
                whereValue = "('" + String.join("','", value.split(",")) + "')";
            } else if (StringUtils.containsIgnoreCase(item.getTerm(), "like")) {
                whereValue = "'%" + value + "%'";
            } else {
                if (field.getDeType() == 1) {
                    whereValue = String.format(SlsConstants.STR_TO_DATE, "'" + value + "'", SlsConstants.DEFAULT_DATE_FORMAT);
                } else {
                    whereValue = String.format(SlsConstants.WHERE_VALUE_VALUE, value);
                }
            }
            SQLObj build = SQLObj.builder()
                    .whereField(whereName)
                    .whereTermAndValue(whereTerm + whereValue)
                    .build();
            res = build.getWhereField() + " " + build.getWhereTermAndValue();
        }
        return res;
    }

    @Override
    public String convertTableToSql(String tableName, Datasource ds) {
        String schema = new Gson().fromJson(ds.getConfiguration(), JdbcConfiguration.class).getSchema();
        schema = String.format(SlsConstants.KEYWORD_TABLE, schema);
        return createSQLPreview(
                "SELECT * FROM " + schema + "." + String.format(SlsConstants.KEYWORD_TABLE, tableName), null);
    }

    public String transMysqlFilterTerm(String term) {
        switch (term) {
            case "eq":
                return " = ";
            case "not_eq":
                return " <> ";
            case "lt":
                return " < ";
            case "le":
                return " <= ";
            case "gt":
                return " > ";
            case "ge":
                return " >= ";
            case "in":
                return " IN ";
            case "not in":
                return " NOT IN ";
            case "like":
                return " LIKE ";
            case "not like":
                return " NOT LIKE ";
            case "null":
                return " IS NULL ";
            case "not_null":
                return " IS NOT NULL ";
            case "empty":
                return " = ";
            case "not_empty":
                return " <> ";
            case "between":
                return " BETWEEN ";
            default:
                return "";
        }
    }

    public String transCustomFilterList(SQLObj tableObj, List<ChartFieldCustomFilterDTO> requestList) {
        if (CollectionUtils.isEmpty(requestList)) {
            return null;
        }
        List<String> res = new ArrayList<>();
        for (ChartFieldCustomFilterDTO request : requestList) {
            List<SQLObj> list = new ArrayList<>();
            DatasetTableField field = request.getField();

            if (ObjectUtils.isEmpty(field)) {
                continue;
            }
            String whereName = "";
            String originName;
            if (ObjectUtils.isNotEmpty(field.getExtField()) && field.getExtField() == 2) {
                // 解析origin name中有关联的字段生成sql表达式
                originName = calcFieldRegex(field.getOriginName(), tableObj);
            } else if (ObjectUtils.isNotEmpty(field.getExtField()) && field.getExtField() == 1) {
                originName = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                        field.getOriginName());
            } else {
                originName = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                        field.getOriginName());
            }
            Boolean numberValueFlag = false;
            if (field.getDeType() == 1) {
                if (field.getDeExtractType() == 0 || field.getDeExtractType() == 5) {
                    numberValueFlag = true;
                    whereName = String.format(SlsConstants.STR_TO_DATE, originName, StringUtils.isNotEmpty(field.getDateFormat()) ? field.getDateFormat() : SlsConstants.DEFAULT_DATE_FORMAT);
                }
                if (field.getDeExtractType() == 2 || field.getDeExtractType() == 3 || field.getDeExtractType() == 4) {
                    String cast = String.format(SlsConstants.CAST, originName, SlsConstants.DEFAULT_INT_FORMAT)
                            + "/1000";
                    whereName = String.format(SlsConstants.FROM_UNIXTIME, cast, SlsConstants.DEFAULT_DATE_FORMAT);
                }
                if (field.getDeExtractType() == 1) {
                    whereName = originName;
                }
            } else if (field.getDeType() == 2 || field.getDeType() == 3) {
                if (field.getDeExtractType() == 0 || field.getDeExtractType() == 5) {
                    numberValueFlag = true;
                    if (field.getDeType() == 2) {
                        whereName = String.format(SlsConstants.CAST, originName, SlsConstants.DEFAULT_INT_FORMAT);
                    } else {
                        whereName = String.format(SlsConstants.CAST, originName, SlsConstants.DEFAULT_FLOAT_FORMAT);
                    }
                }
                if (field.getDeExtractType() == 1) {
                    whereName = String.format(SlsConstants.UNIX_TIMESTAMP, originName) + "*1000";
                }
                if (field.getDeExtractType() == 2 || field.getDeExtractType() == 3 || field.getDeExtractType() == 4) {
                    whereName = originName;
                }
            } else {
                whereName = originName;
            }

            if (StringUtils.equalsIgnoreCase(request.getFilterType(), "enum")) {
                if (CollectionUtils.isNotEmpty(request.getEnumCheckField())) {
                    res.add("(" + whereName + " IN ('" + String.join("','", request.getEnumCheckField()) + "'))");
                }
            } else {
                List<ChartCustomFilterItemDTO> filter = request.getFilter();
                for (ChartCustomFilterItemDTO filterItemDTO : filter) {
                    String value = filterItemDTO.getValue();
                    String whereTerm = transMysqlFilterTerm(filterItemDTO.getTerm());
                    String whereValue = "";

                    if (StringUtils.equalsIgnoreCase(filterItemDTO.getTerm(), "null")) {
                        whereValue = "";
                    } else if (StringUtils.equalsIgnoreCase(filterItemDTO.getTerm(), "not_null")) {
                        whereValue = "";
                    } else if (StringUtils.equalsIgnoreCase(filterItemDTO.getTerm(), "empty")) {
                        whereValue = "''";
                    } else if (StringUtils.equalsIgnoreCase(filterItemDTO.getTerm(), "not_empty")) {
                        whereValue = "''";
                    } else if (StringUtils.containsIgnoreCase(filterItemDTO.getTerm(), "in")
                            || StringUtils.containsIgnoreCase(filterItemDTO.getTerm(), "not in")) {
                        whereValue = "('" + String.join("','", value.split(",")) + "')";
                    } else if (StringUtils.containsIgnoreCase(filterItemDTO.getTerm(), "like")) {
                        whereValue = "'%" + value + "%'";
                    } else {
                        if (field.getDeType() == 1) {
                            whereValue = String.format(SlsConstants.STR_TO_DATE, "'" + value + "'", StringUtils.isNotEmpty(field.getDateFormat()) ? field.getDateFormat() : SlsConstants.DEFAULT_DATE_FORMAT);
                        } else if (numberValueFlag || StringUtils.equalsIgnoreCase(value, "null")) {
                            whereValue = String.format(SlsConstants.WHERE_NUMBER_VALUE, value);
                        } else {
                            whereValue = String.format(SlsConstants.WHERE_VALUE_VALUE, value);
                        }
                    }
                    list.add(SQLObj.builder()
                            .whereField(whereName)
                            .whereTermAndValue(whereTerm + whereValue)
                            .build());
                }

                List<String> strList = new ArrayList<>();
                list.forEach(ele -> strList.add(ele.getWhereField() + " " + ele.getWhereTermAndValue()));
                if (CollectionUtils.isNotEmpty(list)) {
                    res.add("(" + String.join(" " + getLogic(request.getLogic()) + " ", strList) + ")");
                }
            }
        }
        return CollectionUtils.isNotEmpty(res) ? "(" + String.join(" AND ", res) + ")" : null;
    }

    public String transExtFilterList(SQLObj tableObj, List<ChartExtFilterRequest> requestList) {
        if (CollectionUtils.isEmpty(requestList)) {
            return null;
        }
        List<SQLObj> list = new ArrayList<>();
        for (ChartExtFilterRequest request : requestList) {
            List<String> value = request.getValue();

            List<String> whereNameList = new ArrayList<>();
            List<DatasetTableField> fieldList = new ArrayList<>();
            if (request.getIsTree()) {
                fieldList.addAll(request.getDatasetTableFieldList());
            } else {
                fieldList.add(request.getDatasetTableField());
            }

            Boolean numberValueFlag = false;
            for (DatasetTableField field : fieldList) {
                if (CollectionUtils.isEmpty(value) || ObjectUtils.isEmpty(field)) {
                    continue;
                }
                String whereName = "";

                String originName;
                if (ObjectUtils.isNotEmpty(field.getExtField()) && field.getExtField() == 2) {
                    // 解析origin name中有关联的字段生成sql表达式
                    originName = calcFieldRegex(field.getOriginName(), tableObj);
                } else if (ObjectUtils.isNotEmpty(field.getExtField()) && field.getExtField() == 1) {
                    originName = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            field.getOriginName());
                } else {
                    originName = String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(),
                            field.getOriginName());
                }

                if (field.getDeType() == 1) {
                    if (field.getDeExtractType() == 0 || field.getDeExtractType() == 5) {
                        whereName = String.format(SlsConstants.STR_TO_DATE, originName, StringUtils.isNotEmpty(field.getDateFormat()) ? field.getDateFormat() : SlsConstants.DEFAULT_DATE_FORMAT);
                    }
                    if (field.getDeExtractType() == 2 || field.getDeExtractType() == 3
                            || field.getDeExtractType() == 4) {
                        String cast = String.format(SlsConstants.CAST, originName,
                                SlsConstants.DEFAULT_INT_FORMAT) + "/1000";
                        whereName = String.format(SlsConstants.FROM_UNIXTIME, cast,
                                SlsConstants.DEFAULT_DATE_FORMAT);
                    }
                    if (field.getDeExtractType() == 1) {
                        whereName = originName;
                    }
                } else if (field.getDeType() == 2 || field.getDeType() == 3) {
                    if (field.getDeExtractType() == 0 || field.getDeExtractType() == 5) {
                        whereName = String.format(SlsConstants.CAST, originName, SlsConstants.DEFAULT_FLOAT_FORMAT);
                        numberValueFlag = true;
                    }
                    if (field.getDeExtractType() == 1) {
                        whereName = String.format(SlsConstants.UNIX_TIMESTAMP, originName) + "*1000";
                    }
                    if (field.getDeExtractType() == 2 || field.getDeExtractType() == 3
                            || field.getDeExtractType() == 4) {
                        whereName = originName;
                    }
                } else {
                    whereName = originName;
                }
                whereNameList.add(whereName);
            }

            String whereName = "";
            if (request.getIsTree()) {
                whereName = StringUtils.join(whereNameList, "||','||");
            } else {
                whereName = whereNameList.get(0);
            }
            String whereTerm = transMysqlFilterTerm(request.getOperator());
            String whereValue = "";

            if (StringUtils.containsIgnoreCase(request.getOperator(), "in")) {
                whereValue = "('" + StringUtils.join(value, "','") + "')";
            } else if (StringUtils.containsIgnoreCase(request.getOperator(), "like")) {
                whereValue = "'%" + value.get(0) + "%'";
            } else if (StringUtils.containsIgnoreCase(request.getOperator(), "between")) {
                if (request.getDatasetTableField().getDeType() == 1) {
                    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    String startTime = simpleDateFormat.format(new Date(Long.parseLong(value.get(0))));
                    String endTime = simpleDateFormat.format(new Date(Long.parseLong(value.get(1))));
                    String st = String.format(SlsConstants.STR_TO_DATE, "'" + startTime + "'",
                            SlsConstants.DEFAULT_DATE_FORMAT);
                    String et = String.format(SlsConstants.STR_TO_DATE, "'" + endTime + "'",
                            SlsConstants.DEFAULT_DATE_FORMAT);
                    whereValue = st + " AND " + et;
                } else {
                    if (numberValueFlag) {
                        whereValue = String.format(SlsConstants.WHERE_NUMBER_BETWEEN, value.get(0), value.get(1));
                    } else {
                        whereValue = String.format(SlsConstants.WHERE_BETWEEN, value.get(0), value.get(1));
                    }
                }
            } else {
                if (numberValueFlag || StringUtils.equalsIgnoreCase(value.get(0), "null")) {
                    whereValue = String.format(SlsConstants.WHERE_NUMBER_VALUE, value.get(0));
                } else {
                    whereValue = String.format(SlsConstants.WHERE_VALUE_VALUE, value.get(0));
                }
            }
            list.add(SQLObj.builder()
                    .whereField(whereName)
                    .whereTermAndValue(whereTerm + whereValue)
                    .build());
        }
        List<String> strList = new ArrayList<>();
        list.forEach(ele -> strList.add(ele.getWhereField() + " " + ele.getWhereTermAndValue()));
        return CollectionUtils.isNotEmpty(list) ? "(" + String.join(" AND ", strList) + ")" : null;
    }

    private String sqlFix(String sql) {
        if (sql.lastIndexOf(";") == (sql.length() - 1)) {
            sql = sql.substring(0, sql.length() - 1);
        }
        return sql;
    }

    private String transDateFormat(String dateStyle, String datePattern) {
        String split = "-";
        if (StringUtils.equalsIgnoreCase(datePattern, "date_sub")) {
            split = "-";
        } else if (StringUtils.equalsIgnoreCase(datePattern, "date_split")) {
            split = "/";
        } else {
            split = "-";
        }

        if (StringUtils.isEmpty(dateStyle)) {
            return SlsConstants.DEFAULT_DATE_FORMAT;
        }

        switch (dateStyle) {
            case "y":
                return "%Y";
            case "y_M":
                return "%Y" + split + "%m";
            case "y_M_d":
                return "%Y" + split + "%m" + split + "%d";
            case "H_m_s":
                return "%H:%i:%S";
            case "y_M_d_H_m":
                return "%Y" + split + "%m" + split + "%d" + " %H:%i";
            case "y_M_d_H_m_s":
                return "%Y" + split + "%m" + split + "%d" + " %H:%i:%S";
            default:
                return SlsConstants.DEFAULT_DATE_FORMAT;
        }
    }

    private SQLObj getXFields(ChartViewFieldDTO x, String originField, String fieldAlias) {
        String fieldName = "";
        if (x.getDeExtractType() == DeTypeConstants.DE_TIME) {
            if (x.getDeType() == DeTypeConstants.DE_INT || x.getDeType() == DeTypeConstants.DE_FLOAT) {
                fieldName = String.format(SlsConstants.UNIX_TIMESTAMP, originField) + "*1000";
            } else if (x.getDeType() == DeTypeConstants.DE_TIME) {
                String format = transDateFormat(x.getDateStyle(), x.getDatePattern());
                fieldName = String.format(SlsConstants.DATE_FORMAT, originField, format);
            } else {
                fieldName = originField;
            }
        } else {
            if (x.getDeType() == DeTypeConstants.DE_TIME) {
                String format = transDateFormat(x.getDateStyle(), x.getDatePattern());
                if (x.getDeExtractType() == DeTypeConstants.DE_STRING) {
                    fieldName = String.format(SlsConstants.DATE_FORMAT, originField, format);
                } else {
                    String cast = String.format(SlsConstants.CAST, originField, SlsConstants.DEFAULT_INT_FORMAT) + "/1000";
                    String from_unixtime = String.format(SlsConstants.FROM_UNIXTIME, cast, SlsConstants.DEFAULT_DATE_FORMAT);
                    fieldName = String.format(SlsConstants.DATE_FORMAT, from_unixtime, format);
                }
            } else if (x.getDeType() == DeTypeConstants.DE_STRING && x.getDeExtractType() == DeTypeConstants.DE_STRING) {
                fieldName = String.format(SlsConstants.CAST, originField, SlsConstants.CHAR);
            } else {
                if (x.getDeType() == DeTypeConstants.DE_INT) {
                    fieldName = String.format(SlsConstants.CAST, originField, SlsConstants.DEFAULT_INT_FORMAT);
                } else if (x.getDeType() == DeTypeConstants.DE_FLOAT) {
                    fieldName = String.format(SlsConstants.CAST, originField, SlsConstants.DEFAULT_FLOAT_FORMAT);
                } else {
                    fieldName = originField;
                }
            }
        }
        return SQLObj.builder()
                .fieldName(fieldName)
                .fieldAlias(fieldAlias)
                .build();
    }

    private List<SQLObj> getXWheres(ChartViewFieldDTO x, String originField, String fieldAlias) {
        List<SQLObj> list = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(x.getFilter()) && x.getFilter().size() > 0) {
            x.getFilter().forEach(f -> {
                String whereName = "";
                String whereTerm = transMysqlFilterTerm(f.getTerm());
                String whereValue = "";
                if (x.getDeType() == 1 && x.getDeExtractType() != 1) {
                    String cast = String.format(SlsConstants.CAST, originField, SlsConstants.DEFAULT_INT_FORMAT)
                            + "/1000";
                    whereName = String.format(SlsConstants.FROM_UNIXTIME, cast, SlsConstants.DEFAULT_DATE_FORMAT);
                } else {
                    whereName = originField;
                }
                if (StringUtils.equalsIgnoreCase(f.getTerm(), "null")) {
                    whereValue = "";
                } else if (StringUtils.equalsIgnoreCase(f.getTerm(), "not_null")) {
                    whereValue = "";
                } else if (StringUtils.equalsIgnoreCase(f.getTerm(), "empty")) {
                    whereValue = "''";
                } else if (StringUtils.equalsIgnoreCase(f.getTerm(), "not_empty")) {
                    whereValue = "''";
                } else if (StringUtils.containsIgnoreCase(f.getTerm(), "in")) {
                    whereValue = "('" + StringUtils.join(f.getValue(), "','") + "')";
                } else if (StringUtils.containsIgnoreCase(f.getTerm(), "like")) {
                    whereValue = "'%" + f.getValue() + "%'";
                } else {
                    whereValue = String.format(SlsConstants.WHERE_VALUE_VALUE, f.getValue());
                }
                list.add(SQLObj.builder()
                        .whereField(whereName)
                        .whereAlias(fieldAlias)
                        .whereTermAndValue(whereTerm + whereValue)
                        .build());
            });
        }
        return list;
    }

    private SQLObj getYFields(ChartViewFieldDTO y, String originField, String fieldAlias) {
        String fieldName = "";
        if (StringUtils.equalsIgnoreCase(y.getOriginName(), "*")) {
            fieldName = SlsConstants.AGG_COUNT;
        } else if (SQLConstants.DIMENSION_TYPE.contains(y.getDeType())) {
            if (StringUtils.equalsIgnoreCase(y.getSummary(), "count_distinct")) {
                fieldName = String.format(SlsConstants.AGG_FIELD, "COUNT", "DISTINCT " + originField);
            } else if (StringUtils.equalsIgnoreCase(y.getSummary(), "group_concat")) {
                fieldName = String.format(SlsConstants.GROUP_CONCAT, originField, originField);
            } else {
                fieldName = String.format(SlsConstants.AGG_FIELD, y.getSummary(), originField);
            }
        } else {
            if (StringUtils.equalsIgnoreCase(y.getSummary(), "avg")
                    || StringUtils.containsIgnoreCase(y.getSummary(), "pop")) {
                String cast = String.format(SlsConstants.CAST, originField,
                        y.getDeType() == 2 ? SlsConstants.DEFAULT_INT_FORMAT : SlsConstants.DEFAULT_FLOAT_FORMAT);
                String agg = String.format(SlsConstants.AGG_FIELD, y.getSummary(), cast);
                fieldName = String.format(SlsConstants.CAST, agg, SlsConstants.DEFAULT_FLOAT_FORMAT);
            } else {
                String cast = String.format(SlsConstants.CAST, originField,
                        y.getDeType() == 2 ? SlsConstants.DEFAULT_INT_FORMAT : SlsConstants.DEFAULT_FLOAT_FORMAT);
                if (StringUtils.equalsIgnoreCase(y.getSummary(), "count_distinct")) {
                    fieldName = String.format(SlsConstants.AGG_FIELD, "COUNT", "DISTINCT " + cast);
                } else {
                    fieldName = String.format(SlsConstants.AGG_FIELD, y.getSummary(), cast);
                }
            }
        }
        return SQLObj.builder()
                .fieldName(fieldName)
                .fieldAlias(fieldAlias)
                .build();
    }

    private String getYWheres(ChartViewFieldDTO y, String originField, String fieldAlias) {
        List<SQLObj> list = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(y.getFilter()) && y.getFilter().size() > 0) {
            y.getFilter().forEach(f -> {
                String whereTerm = transMysqlFilterTerm(f.getTerm());
                String whereValue = "";
                // 原始类型不是时间，在de中被转成时间的字段做处理
                if (StringUtils.equalsIgnoreCase(f.getTerm(), "null")) {
                    whereValue = "";
                } else if (StringUtils.equalsIgnoreCase(f.getTerm(), "not_null")) {
                    whereValue = "";
                } else if (StringUtils.equalsIgnoreCase(f.getTerm(), "empty")) {
                    whereValue = "''";
                } else if (StringUtils.equalsIgnoreCase(f.getTerm(), "not_empty")) {
                    whereValue = "''";
                } else if (StringUtils.containsIgnoreCase(f.getTerm(), "in")) {
                    whereValue = "('" + StringUtils.join(f.getValue(), "','") + "')";
                } else if (StringUtils.containsIgnoreCase(f.getTerm(), "like")) {
                    whereValue = "'%" + f.getValue() + "%'";
                } else {
                    whereValue = String.format(SlsConstants.WHERE_VALUE_VALUE, f.getValue());
                }
                list.add(SQLObj.builder()
                        .whereField(fieldAlias)
                        .whereAlias(fieldAlias)
                        .whereTermAndValue(whereTerm + whereValue)
                        .build());
            });
        }
        List<String> strList = new ArrayList<>();
        list.forEach(ele -> strList.add(ele.getWhereField() + " " + ele.getWhereTermAndValue()));
        return CollectionUtils.isNotEmpty(list) ? "(" + String.join(" " + getLogic(y.getLogic()) + " ", strList) + ")"
                : null;
    }

    private String calcFieldRegex(String originField, SQLObj tableObj) {
        originField = originField.replaceAll("[\\t\\n\\r]]", "");
        // 正则提取[xxx]
        String regex = "\\[(.*?)]";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(originField);
        Set<String> ids = new HashSet<>();
        while (matcher.find()) {
            String id = matcher.group(1);
            ids.add(id);
        }
        if (CollectionUtils.isEmpty(ids)) {
            return originField;
        }
        DatasetTableFieldExample datasetTableFieldExample = new DatasetTableFieldExample();
        datasetTableFieldExample.createCriteria().andIdIn(new ArrayList<>(ids));
        List<DatasetTableField> calcFields = datasetTableFieldMapper.selectByExample(datasetTableFieldExample);
        for (DatasetTableField ele : calcFields) {
            originField = originField.replaceAll("\\[" + ele.getId() + "]",
                    String.format(SlsConstants.KEYWORD_FIX, tableObj.getTableAlias(), ele.getOriginName()));
        }
        return originField;
    }

    private String sqlLimit(String sql, ChartViewWithBLOBs view) {
        if (StringUtils.equalsIgnoreCase(view.getResultMode(), "custom")) {
            return replaceSql("SELECT * FROM (" + sqlFix(sql) + ") DE_RESULT_TMP " + " limit " + view.getResultCount());
        } else {
            return replaceSql(sql);
        }
    }

    @Override
    public String sqlForPreview(String table, Datasource ds) {
        String resultSql = "";
        if (StringUtils.isNotEmpty(table)) {
            resultSql = "*| select * from " + table;
        } else {
            resultSql = "*| select * ";
        }

        return resultSql;
    }

    @Override
    public String getResultCount(boolean isTable, String sql, List<ChartViewFieldDTO> xAxis, List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList, Datasource ds, ChartViewWithBLOBs view) {
        if (isTable) {
            return concatSql("SELECT COUNT(*) from (" + getSQLTableInfo(sql, xAxis, fieldCustomFilter, rowPermissionsTree, extFilterRequestList, ds, view) + ") COUNT_TEMP");
        } else {
            return concatSql("SELECT COUNT(*) from (" + getSQLAsTmpTableInfoForCount(sql, xAxis, fieldCustomFilter, rowPermissionsTree, extFilterRequestList, ds, view) + ") COUNT_TEMP");
        }
    }

    private String getSQLAsTmpTableInfoForCount(String sql, List<ChartViewFieldDTO> xAxis,
                                       List<ChartFieldCustomFilterDTO> fieldCustomFilter, List<DataSetRowPermissionsTreeDTO> rowPermissionsTree, List<ChartExtFilterRequest> extFilterRequestList,
                                       Datasource ds, ChartViewWithBLOBs view) {
        return getSQLTableInfo("(" + sqlFix(sql) + ")", xAxis, fieldCustomFilter, rowPermissionsTree, extFilterRequestList, null, view);
    }

    private String replaceSql(String sql) {
        Map<String, String> map = SlsConstants.replaceMap;
        for (String key : map.keySet()) {
            sql = sql.replace(key, map.get(key));
        }
        return sql;
    }

    public List<Dateformat> dateformat() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<Dateformat> dateformats = new ArrayList<>();
        try{
            dateformats = objectMapper.readValue("[\n" +
                    "{\"dateformat\": \"%Y-%m-%d\"},\n" +
                    "{\"dateformat\": \"%Y/%m/%d\"},\n" +
                    "{\"dateformat\": \"%Y%m%d\"},\n" +
                    "{\"dateformat\": \"%Y-%m-%d %H:%i:%S\"},\n" +
                    "{\"dateformat\": \"%Y/%m/%d %H:%i:%S\"},\n" +
                    "{\"dateformat\": \"%Y%m%d %H:%i:%S\"}\n" +
                    "]", new TypeReference<List<Dateformat>>() {} );
        }catch (Exception e){}
        return dateformats;
    }

    private String concatSql(String sql) {
        if (StringUtils.isNotEmpty(sql)) {
            return "*| " + sql.replace("*|", "");
        }
        return sql;
    }
}
