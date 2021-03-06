/*-
 * <<
 * DBus
 * ==
 * Copyright (C) 2016 - 2017 Bridata
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package com.creditease.dbus.ws.service.table;

import com.creditease.dbus.commons.SupportedMysqlDataType;
import com.creditease.dbus.commons.SupportedOraDataType;
import com.creditease.dbus.enums.DbusDatasourceType;
import com.creditease.dbus.ws.domain.DataTable;
import com.creditease.dbus.ws.domain.DbusDataSource;
import com.creditease.dbus.ws.domain.TableMeta;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public abstract class TableFetcher {
    private DbusDataSource ds;
    private Connection conn;

    public TableFetcher(DbusDataSource ds) {
        this.ds = ds;
    }

    public abstract String buildQuery(Object... args);
    public abstract String fillParameters(PreparedStatement statement, Map<String, Object> params) throws Exception;
    public abstract String buildTableFieldQuery(Object... args);
    public abstract String fillTableParameters(PreparedStatement statement, Map<String, Object> params) throws Exception;

    public List<DataTable> fetchTable(Map<String, Object> params) throws Exception {
        try {
            PreparedStatement statement = conn.prepareStatement(buildQuery(params));
            fillParameters(statement, params);
            ResultSet resultSet = statement.executeQuery();
            if(ds.getDsType().equals("mysql")){
                return buildResultMySQL(resultSet);
            }else{
                return buildResultOracle(resultSet);
            }
        } finally {
            if (!conn.isClosed()) {
                conn.close();
            }
        }
    }

    /**
     *
     * @param params
     * @return table所有字段
     * @throws Exception
     */
    public List<TableMeta> fetchTableField(Map<String, Object> params) throws Exception {
        try {
            PreparedStatement statement = conn.prepareStatement(buildTableFieldQuery(params));
            fillTableParameters(statement, params);
            ResultSet resultSet = statement.executeQuery();
            if(ds.getDsType().equals("mysql")){
                return tableFieldMysql(resultSet);
            }else{
                return tableFieldOracle(resultSet);
            }
        } finally {
            if (!conn.isClosed()) {
                conn.close();
            }
        }
    }

    public static TableFetcher getFetcher(DbusDataSource ds) throws Exception {
        TableFetcher fetcher;
        DbusDatasourceType dsType = DbusDatasourceType.parse(ds.getDsType());
        switch (dsType) {
            case MYSQL:
                Class.forName("com.mysql.jdbc.Driver");
                fetcher = new MySqlTableFetcher(ds);
                break;
            case ORACLE:
                Class.forName("oracle.jdbc.driver.OracleDriver");
                fetcher = new OracleTableFetcher(ds);
                break;
            default:
                throw new IllegalArgumentException();
        }
        Connection conn = DriverManager.getConnection(ds.getMasterURL(), ds.getDbusUser(), ds.getDbusPassword());
        fetcher.setConnection(conn);
        return fetcher;
    }

    protected void setConnection(Connection conn) {
        this.conn = conn;
    }

    protected List<DataTable> buildResultMySQL(ResultSet rs) throws SQLException {
        List<DataTable> list = new ArrayList<>();
        DataTable table;
        while (rs.next()) {
            table = new DataTable();
            table.setTableName(rs.getString("TABLE_NAME"));
            table.setPhysicalTableRegex(rs.getString("TABLE_NAME"));
            table.setVerID(null);
            table.setStatus("ok");
            table.setCreateTime(new java.util.Date());
            list.add(table);
        }
        return list;
    }

    /**
     *
     * @param rs
     * @return table所有字段
     * @throws SQLException
     */
    protected List<TableMeta> tableFieldMysql(ResultSet rs) throws SQLException {
        List<TableMeta> list = new ArrayList<>();
        TableMeta tableMeta ;
        while(rs.next())
        {
            tableMeta = new TableMeta();
            tableMeta.setColumnName(rs.getString("COLUMN_NAME"));
            tableMeta.setDataType(rs.getString("DATA_TYPE"));
            if(!SupportedMysqlDataType.isSupported(rs.getString("DATA_TYPE")))
                list.add(tableMeta);
        }
        //System.out.println(list.size());
        return list;
    }


    /**
     * 判断是否不兼容
     */
    protected boolean isIncompatibleCol(String str)
    {
        if("Anydata".equals(str)||"Anytype".equals(str)||"XMLType".equals(str))
        {
            return true;
        }
        return false;
    }
    /**
     *
     * @param rs
     * @return table所有字段
     * @throws SQLException
     */
    protected List<TableMeta> tableFieldOracle(ResultSet rs) throws SQLException {
        List<TableMeta> list = new ArrayList<>();
        TableMeta tableMeta ;
        while(rs.next())
        {
            tableMeta = new TableMeta();
            tableMeta.setColumnName(rs.getString("COLUMN_NAME"));
            tableMeta.setDataType(rs.getString("DATA_TYPE"));
            if(isIncompatibleCol(rs.getString("DATA_TYPE")))
                 tableMeta.setIncompatibleColumn(rs.getString("COLUMN_NAME") +"/" + rs.getString("DATA_TYPE"));
            if(!SupportedOraDataType.isSupported(rs.getString("DATA_TYPE")))
                list.add(tableMeta);
        }
        return list;
    }

    protected List<DataTable> buildResultOracle(ResultSet rs) throws SQLException {
        List<DataTable> list = new ArrayList<>();
        DataTable table;
        while (rs.next()) {
            table = new DataTable();
            table.setTableName(rs.getString("TABLE_NAME"));
            table.setPhysicalTableRegex(rs.getString("TABLE_NAME"));
            table.setVerID(null);
            table.setStatus("ok");
            table.setCreateTime(new java.util.Date());
            list.add(table);
        }
        return list;
    }

    public List<Map<String, Object>> fetchTableColumn(Map<String, Object> params) throws Exception {
        try {

            String sql = params.get("sql").toString();

            //分割字符串，后续查找的字符串必须整体对应，避免出现从XXX_ID找到ID的情况
            String[] sqls = sql.split(" |,"); //为保证一致性，实际添加的字符串的大小写和原始字符串应当相同
            String[] sqlsUpperCase = sql.toUpperCase().split(" |,");//为了方便比较，此处将字符串全部转为大写字母，以便后续比较忽略大小写

            //不允许除select以外的语句执行
            if (findString("select", sqlsUpperCase) != -1) {

                if (findString(";", sqlsUpperCase) != -1) {
                    sql = sql.substring(0, sql.length() - 1);
                }
                if (params.get("dsType").toString().equals("oracle")) {
                    //如果是oracle
                    if (findString("where", sqlsUpperCase) != -1) {
                        sql = sql + " and " + sqls[findString("where", sqlsUpperCase)] + " rownum <= 100 ";
                    } else {
                        sql = sql + " WHERE " + " rownum <= 100 ";
                    }


                    //如果存在ID或SCN_NO，则按其倒排；否则按创建时间倒排
                    if (findString("ID", sqlsUpperCase) != -1) {
                        sql = sql + " order by " + sqls[findString("ID", sqlsUpperCase)] + " desc";
                    } else if (findString("SCN_NO", sqlsUpperCase) != -1) {
                        sql = sql + " order by " + sqls[findString("SEQNO", sqlsUpperCase)] + " desc";
                        //sql = sql + " order by " + sqls[findString("SERNO", sqlsUpperCase)] + " desc";
                    } else if (findString("CREATE_TIME", sqlsUpperCase) != -1) {
                        if (findString("PULL_REQ_CREATE_TIME", sqlsUpperCase) != -1) {
                            sql = sql + " order by " + sqls[findString("PULL_REQ_CREATE_TIME", sqlsUpperCase)] + " desc";
                        } else {
                            sql = sql + " order by " + sqls[findString("CREATE_TIME", sqlsUpperCase)] + " desc";
                        }
                    } else if (findString("EVENT_TIME", sqlsUpperCase) != -1) {
                        sql = sql + " order by " + sqls[findString("EVENT_TIME", sqlsUpperCase)] + " desc";
                    } else if (findString("DDL_TIME", sqlsUpperCase) != -1) {
                        sql = sql + " order by " + sqls[findString("DDL_TIME", sqlsUpperCase)] + " desc";
                    } else if (findString("DATE_TYPE", sqlsUpperCase) != -1) {
                        sql = sql + " order by " + sqls[findString("DATE_TYPE", sqlsUpperCase)] + " desc";
                    } else if (findString("PULL_REQ_CREATE_TIME", sqlsUpperCase) != -1) {
                        sql = sql + " order by " + sqls[findString("PULL_REQ_CREATE_TIME", sqlsUpperCase)] + " desc";
                    }
                } else {
                    //如果是mysql
                    if (findString("ID", sqlsUpperCase) != -1) {
                        sql = sql + " order by " + sqls[findString("ID", sqlsUpperCase)] + " desc";
                    } else if (findString("SCN_NO", sqlsUpperCase) != -1) {
                        sql = sql + " order by " + sqls[findString("SEQNO", sqlsUpperCase)] + " desc";
                    } else if (findString("create_time", sqlsUpperCase) != -1) {
                        if (findString("pull_req_create_time", sqlsUpperCase) != -1) {
                            sql = sql + " order by " + sqls[findString("pull_req_create_time", sqlsUpperCase)] + " desc";
                        } else {
                            sql = sql + " order by " + sqls[findString("create_time", sqlsUpperCase)] + " desc";
                        }
                    }
                    sql = sql + " limit 100";
                }

                PreparedStatement statement = conn.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery();
                List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
                ResultSetMetaData md = resultSet.getMetaData(); //获得结果集结构信息,元数据
                int columnCount = md.getColumnCount();   //获得列数
                boolean flag = false; //判断表格是否为空
                while (resultSet.next()) {
                    flag = true;
                    Map<String, Object> rowData = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        if (md.getColumnName(i).indexOf("TIME") != -1 && params.get("dsType").toString().equals("oracle")) {
                            if (sql.indexOf("HEARTBEAT") != -1) {
                                rowData.put(md.getColumnName(i), resultSet.getObject(i));
                            } else {
                                rowData.put(md.getColumnName(i), resultSet.getTimestamp(md.getColumnName(i)));
                            }
                        } else {
                            rowData.put(md.getColumnName(i), resultSet.getObject(i));
                        }
                    }
                    list.add(rowData);
                }
                //表为空，取不到结果集结构，所以将sql语句中的列名取出
                if (!(flag)) {
                    String columns = sql.substring(sql.indexOf("select") + 6, sql.indexOf("from"));
                    String[] column = columns.split(",");
                    Map<String, Object> rowName = new HashMap<String, Object>();
                    for (int j = 0; j < column.length; j++) {
                        rowName.put(column[j], null);
                    }
                    list.add(rowName);
                }
                return list;
            } else {
                return null;
            }

        } finally {
            if (!conn.isClosed()) {
                conn.close();
            }
        }
    }

    public List<Map<String, Object>> fetchTableColumnsInformation(DataTable tableInformation) throws SQLException {
        try {
            String sql = "";
            if (tableInformation.getDsType().equals("oracle")) {
                sql = "select COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE from ALL_TAB_COLUMNS where OWNER= '" + tableInformation.getSchemaName() +
                        "' and TABLE_NAME='" + tableInformation.getTableName() + "'";
            } else if (tableInformation.getDsType().equals("mysql")) {
                sql = " SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH DATA_LENGTH, NUMERIC_PRECISION DATA_PRECISION, NUMERIC_SCALE DATA_SCALE FROM information_schema.`COLUMNS` t_col " +
                        " WHERE t_col.TABLE_SCHEMA = '" + tableInformation.getSchemaName() +
                        "' AND t_col.TABLE_NAME = '" + tableInformation.getTableName() + "'";
            }

            PreparedStatement statement = conn.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery();
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            ResultSetMetaData md = resultSet.getMetaData(); //获得结果集结构信息,元数据
            int columnCount = md.getColumnCount();   //获得列数
            boolean flag = false; //判断表格是否为空
            while (resultSet.next()) {
                flag = true;
                Map<String, Object> rowData = new LinkedHashMap();
                for (int i = 1; i <= columnCount; i++) {
                    rowData.put(md.getColumnLabel(i), resultSet.getObject(i));
                }
                list.add(rowData);
            }
            for(Map<String, Object> column: list) {
                Object dataType = column.get("DATA_TYPE");
                Object dataLength = column.get("DATA_LENGTH");
                Object dataPrecision = column.get("DATA_PRECISION");
                Object dataScale = column.get("DATA_SCALE");

                if(dataLength != null) {
                    column.put("DATA_TYPE", dataType.toString()+"("+ dataLength.toString() +")");
                } else if(dataPrecision!=null || dataScale!=null) {
                    if(dataPrecision == null) dataPrecision = "";
                    if(dataScale == null) dataScale = "";
                    column.put("DATA_TYPE", dataType.toString()+"("+ dataPrecision.toString() +","+dataScale.toString() +")");
                }
            }
            //在DATA_TYPE中添加是否主键
            fetchTableColumnsPrimaryKey(tableInformation, list);

            return list;
        } finally {
            if (!conn.isClosed()) {
                conn.close();
            }
        }
    }

    private void fetchTableColumnsPrimaryKey(DataTable tableInformation, List<Map<String, Object>> columnsInfomation) throws SQLException  {
        try {
            String sql = "";
            if (tableInformation.getDsType().equals("oracle")) {
                sql = "select COLUMN_NAME from all_cons_columns cu, all_constraints au where cu.constraint_name = au.constraint_name and au.constraint_type = 'P' and cu.table_name='"+ tableInformation.getTableName() +"'";
            } else if (tableInformation.getDsType().equals("mysql")) {
                sql = "select COLUMN_NAME from INFORMATION_SCHEMA.COLUMNS where TABLE_NAME='"+tableInformation.getTableName()+"' and COLUMN_KEY='PRI'";
            }

            PreparedStatement statement = conn.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery();

            for(Map<String, Object> column: columnsInfomation) {
                column.put("IS_PRIMARY", "NO");
            }
            while (resultSet.next()) {
                for(Map<String, Object> column: columnsInfomation) {
                    if(column.get("COLUMN_NAME").toString().equals(resultSet.getString("COLUMN_NAME"))) {
                        column.put("IS_PRIMARY", "YES");
                        break;
                    }
                }
            }
        } finally {
            if (!conn.isClosed()) {
                conn.close();
            }
        }
    }

    private int findString(String string, String strings[]) {
        //全部为大写字母
        string = string.toUpperCase();

        for (int i = 0; i < strings.length; i++) {
            if (string.equals(strings[i])) return i;
        }
        return -1;
    }

    public List<List<String>> doSqlRule(String sql, List<List<String>> data) {
        int maxColumn = getMaxColumn(data);
        createSqlTable(maxColumn);
        insertSqlData(data, maxColumn);
        List<List<String>> result = executeSqlRule(sql, data);
        return result;
    }

    private int getMaxColumn(List<List<String>> data) {
        int result = 0;
        for (List<String> list : data) {
            result = Math.max(result, list.size());
        }
        return result;
    }

    private void createSqlTable(int maxColumn) {
        StringBuilder sb = new StringBuilder("c1 varchar(512)");
        for(int i=2;i<=maxColumn;i++) {
            sb.append(",c");
            sb.append(i);
            sb.append(" varchar(512)");
        }
        String sql="create temporary table temp ("+sb.toString()+")DEFAULT CHARSET=utf8;";
        try {
            PreparedStatement statement = conn.prepareStatement(sql);
            statement.execute();
        } catch (SQLException e) {
            LoggerFactory.getLogger(getClass()).warn("Execute create failed");
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
        }
    }

    private void insertSqlData(List<List<String>> data,int maxColumn) {
        Statement statement = null;
        try {
            statement = conn.createStatement();
        } catch (SQLException e) {
            e.printStackTrace();
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
        }
        StringBuilder sb = new StringBuilder();
        for(List<String> list: data) {
            sb.setLength(0);
            for(int i=1;i<=maxColumn;i++) {
                sb.append('\'');
                if(i<=list.size() && list.get(i-1)!=null){
                    sb.append(list.get(i-1));
                }
                sb.append('\'');
                sb.append(',');
            }
            sb.deleteCharAt(sb.length()-1);
            try {
                statement.addBatch("insert into temp values("+sb.toString()+");");
            } catch (SQLException e) {
                e.printStackTrace();
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (SQLException e1) {
                    e1.printStackTrace();
                }
            }
        }
        try {
            statement.executeBatch();
        } catch (SQLException e) {
            LoggerFactory.getLogger(getClass()).warn("Execute insert failed");
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
        }
    }

    private List<List<String>> executeSqlRule(String sql, List<List<String>> data) {
        try {
            PreparedStatement statement = conn.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery();
            ResultSetMetaData metaData = resultSet.getMetaData();

            List<List<String>> result = new ArrayList<>();
            while (resultSet.next()) {
                List<String> list = new ArrayList<>();
                for(int i=1;i<=metaData.getColumnCount();i++) {
                    list.add(resultSet.getString(i));
                }
                result.add(list);
            }
            return result;
        } catch (SQLException e) {
            LoggerFactory.getLogger(getClass()).warn("Execute custom sql command failed");
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
            return null;
        }
    }
}
