/***** BEGIN LICENSE BLOCK *****
 * Copyright (c) 2006-2008 Nick Sieger <nick@nicksieger.com>
 * Copyright (c) 2006-2007 Ola Bini <ola.bini@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 ***** END LICENSE BLOCK *****/

package jdbc_adapter;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.StringReader;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSetMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyHash;
import org.jruby.RubyModule;
import org.jruby.RubyNumeric;
import org.jruby.RubyObjectAdapter;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.RubyTime;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.Java;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.javasupport.JavaObject;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.load.BasicLibraryService;
import org.jruby.util.ByteList;

public class JdbcAdapterInternalService implements BasicLibraryService {
    private static RubyObjectAdapter rubyApi;

    public boolean basicLoad(final Ruby runtime) throws IOException {
        RubyModule jdbcConnection = ((RubyModule)(runtime.getModule("ActiveRecord").getConstant("ConnectionAdapters"))).
            defineClassUnder("JdbcConnection",runtime.getObject(),runtime.getObject().getAllocator());
        jdbcConnection.defineAnnotatedMethods(JdbcAdapterInternalService.class);
        RubyModule jdbcSpec = runtime.getOrCreateModule("JdbcSpec");

        rubyApi = JavaEmbedUtils.newObjectAdapter();
        JdbcMySQLSpec.load(jdbcSpec);
        JdbcDerbySpec.load(jdbcSpec, rubyApi);
        return true;
    }

    private static int whitespace(int start, ByteList bl) {
        int end = bl.begin + bl.realSize;

        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(bl.bytes[i])) return i;
        }

        return end;
    }

    private static byte[] INSERT = new byte[] {'i', 'n', 's', 'e', 'r', 't'};
    private static byte[] SELECT = new byte[] {'s', 'e', 'l', 'e', 'c', 't'};
    private static byte[] SHOW = new byte[] {'s', 'h', 'o', 'w'};

    private static boolean startsWithNoCaseCmp(ByteList bytelist, byte[] compare) {
        int p = whitespace(bytelist.begin, bytelist);

        // What the hell is this for?
        if (bytelist.bytes[p] == '(') p = whitespace(p, bytelist);

        for (int i = 0; i < bytelist.realSize && i < compare.length; i++) {
            if (Character.toLowerCase(bytelist.bytes[p + i]) != compare[i]) return false;
        }

        return true;
    }

    @JRubyMethod(name = "insert?", required = 1, meta = true, frame = false)
    public static IRubyObject insert_p(ThreadContext context, IRubyObject recv, IRubyObject _sql) {
        ByteList sql = rubyApi.convertToRubyString(_sql).getByteList();

        return context.getRuntime().newBoolean(startsWithNoCaseCmp(sql, INSERT));
    }

    @JRubyMethod(name = "select?", required = 1, meta = true, frame = false)
    public static IRubyObject select_p(ThreadContext context, IRubyObject recv, IRubyObject _sql) {
        ByteList sql = rubyApi.convertToRubyString(_sql).getByteList();

        return context.getRuntime().newBoolean(startsWithNoCaseCmp(sql, SELECT) || startsWithNoCaseCmp(sql, SHOW));
    }

    @JRubyMethod(name = "connection", frame = false)
    public static IRubyObject connection(IRubyObject recv) {
        if (getConnection(recv) == null) reconnect(recv);

        return rubyApi.getInstanceVariable(recv, "@connection");
    }

    @JRubyMethod(name = "disconnect!", frame = false)
    public static IRubyObject disconnect(IRubyObject recv) {
        setConnection(recv, null);

        return recv;
    }

    @JRubyMethod(name = "reconnect!")
    public static IRubyObject reconnect(IRubyObject recv) {
        setConnection(recv, getConnectionFactory(recv).newConnection());

        return recv;
    }

    @JRubyMethod(name = "with_connection_retry_guard", frame = true)
    public static IRubyObject with_connection_retry_guard(final ThreadContext context, final IRubyObject recv, final Block block) {
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                return block.call(context, new IRubyObject[] { wrappedConnection(recv, c) });
            }
        });
    }

    private static IRubyObject withConnectionAndRetry(ThreadContext context, IRubyObject recv, SQLBlock block) {
        int tries = 1;
        int i = 0;
        Throwable toWrap = null;
        boolean autoCommit = false;
        while (i < tries) {
            Connection c = getConnection(recv, true);
            try {
                autoCommit = c.getAutoCommit();
                return block.call(c);
            } catch (Exception e) {
                toWrap = e;
                while (toWrap.getCause() != null && toWrap.getCause() != toWrap) {
                    toWrap = toWrap.getCause();
                }
                i++;
                if (autoCommit) {
                    if (i == 1) {
                        tries = (int) rubyApi.convertToRubyInteger(config_value(recv, "retry_count")).getLongValue();
                        if (tries <= 0) {
                            tries = 1;
                        }
                    }
                    if (isConnectionBroken(context, recv, c)) {
                        reconnect(recv);
                    } else {
                        throw wrap(recv, toWrap);
                    }
                }
            }
        }
        throw wrap(recv, toWrap);
    }

    private static SQLBlock tableLookupBlock(final Ruby runtime,
            final String catalog, final String schemapat,
            final String tablepat, final String[] types) {
        return new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                ResultSet rs = null;
                try {
                    DatabaseMetaData metadata = c.getMetaData();
                    String clzName = metadata.getClass().getName().toLowerCase();
                    boolean isOracle = clzName.indexOf("oracle") != -1 || clzName.indexOf("oci") != -1;
                    boolean isPostgres = metadata.getDatabaseProductName().equals("PostgreSQL");

                    String realschema = schemapat;
                    String realtablepat = tablepat;

                    if(metadata.storesUpperCaseIdentifiers()) {
                        if (realschema != null) realschema = realschema.toUpperCase();
                        if (realtablepat != null) realtablepat = realtablepat.toUpperCase();
                    } else if(metadata.storesLowerCaseIdentifiers() && ! isPostgres) {
                        if (null != realschema) realschema = realschema.toLowerCase();
                        if (realtablepat != null) realtablepat = realtablepat.toLowerCase();
                    }

                    if (realschema == null && isOracle) {
                        ResultSet schemas = metadata.getSchemas();
                        String username = metadata.getUserName();
                        while (schemas.next()) {
                            if (schemas.getString(1).equalsIgnoreCase(username)) {
                                realschema = schemas.getString(1);
                                break;
                            }
                        }
                        close(schemas);
                    }
                    rs = metadata.getTables(catalog, realschema, realtablepat, types);
                    List arr = new ArrayList();
                    while (rs.next()) {
                        String name = rs.getString(3).toLowerCase();
                        // Handle stupid Oracle 10g RecycleBin feature
                        if (!isOracle || !name.startsWith("bin$")) {
                            arr.add(RubyString.newUnicodeString(runtime, name));
                        }
                    }
                    return runtime.newArray(arr);
                } finally {
                    close(rs);
                }
            }
        };
    }

    @JRubyMethod(name = "tables", rest = true)
    public static IRubyObject tables(ThreadContext context, final IRubyObject recv, IRubyObject[] args) {
        final Ruby runtime = context.getRuntime();
        
        return withConnectionAndRetry(context, recv, tableLookupBlock(runtime, getCatalog(args),
                getSchemaPattern(args), getTablePattern(args), getTypes(args)));
    }

    private static String getCatalog(IRubyObject[] args) {
        return args.length > 0 ? convertToStringOrNull(args[0]) : null;
    }

    private static String getSchemaPattern(IRubyObject[] args) {
        return args.length > 1 ? convertToStringOrNull(args[1]) : null;
    }

    private static String getTablePattern(IRubyObject[] args) {
        return args.length > 2 ? convertToStringOrNull(args[2]) : null;
    }

    private static String[] getTypes(IRubyObject[] args) {
        String[] types;
        if (args.length > 3) {
            IRubyObject typearr = args[3];
            if (typearr instanceof RubyArray) {
                IRubyObject[] arr = rubyApi.convertToJavaArray(typearr);
                types = new String[arr.length];
                for (int i = 0; i < types.length; i++) {
                    types[i] = arr[i].toString();
                }
            } else {
                types = new String[]{ typearr.toString() };
            }
        } else {
            types = new String[]{"TABLE"};
        }
        return types;
    }

    @JRubyMethod(name = "native_database_types")
    public static IRubyObject native_database_types(IRubyObject recv) {
        return rubyApi.getInstanceVariable(recv, "@tps");
    }

    @JRubyMethod(name = "set_native_database_types")
    public static IRubyObject set_native_database_types(ThreadContext context, IRubyObject recv)
            throws SQLException, IOException {
        Ruby runtime = context.getRuntime();
        IRubyObject types = unmarshalResult(context, getConnection(recv, true).getMetaData().getTypeInfo(), true);
        IRubyObject typeConverter = ((RubyModule) (runtime.getModule("ActiveRecord").getConstant("ConnectionAdapters"))).getConstant("JdbcTypeConverter");
        IRubyObject value = rubyApi.callMethod(rubyApi.callMethod(typeConverter, "new", types), "choose_best_types");
        rubyApi.setInstanceVariable(recv, "@native_types", value);

        return runtime.getNil();
    }

    @JRubyMethod(name = "database_name", frame=false)
    public static IRubyObject database_name(ThreadContext context, IRubyObject recv) throws SQLException {
        Connection connection = getConnection(recv, true);
        String name = connection.getCatalog();

        if (null == name) {
            name = connection.getMetaData().getUserName();

            if (null == name) name = "db1";
        }
        
        return context.getRuntime().newString(name);
    }

    @JRubyMethod(name = "begin")
    public static IRubyObject begin(ThreadContext context, IRubyObject recv) throws SQLException {
        getConnection(recv, true).setAutoCommit(false);
        
        return context.getRuntime().getNil();
    }

    @JRubyMethod(name = "commit")
    public static IRubyObject commit(ThreadContext context, IRubyObject recv) throws SQLException {
        Connection connection = getConnection(recv, true);

        if (!connection.getAutoCommit()) {
            try {
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }
        }
        
        return context.getRuntime().getNil();
    }

    @JRubyMethod(name = "rollback")
    public static IRubyObject rollback(ThreadContext context, IRubyObject recv) throws SQLException {
        Connection connection = getConnection(recv, true);

        if (!connection.getAutoCommit()) {
            try {
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }
        }
        
        return context.getRuntime().getNil();
    }

    @JRubyMethod(name = {"columns", "columns_internal"}, required = 1, optional = 2)
    public static IRubyObject columns_internal(final ThreadContext context, final IRubyObject recv,
            final IRubyObject[] args) throws SQLException, IOException {
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                ResultSet results = null;
                try {
                    String table_name = rubyApi.convertToRubyString(args[0]).getUnicodeValue();
                    String schemaName = null;

                    int index = table_name.indexOf(".");
                    if(index != -1) {
                        schemaName = table_name.substring(0, index);
                        table_name = table_name.substring(index + 1);
                    }

                    DatabaseMetaData metadata = c.getMetaData();
                    String clzName = metadata.getClass().getName().toLowerCase();
                    boolean isDerby = clzName.indexOf("derby") != -1;
                    boolean isOracle = clzName.indexOf("oracle") != -1 || clzName.indexOf("oci") != -1;
                    boolean isPostgres = metadata.getDatabaseProductName().equals("PostgreSQL");

                    if(args.length>2) {
                        schemaName = args[2].toString();
                    }

                    if(metadata.storesUpperCaseIdentifiers()) {
                        if (null != schemaName) schemaName = schemaName.toUpperCase();
                        table_name = table_name.toUpperCase();
                    } else if(metadata.storesLowerCaseIdentifiers() && ! isPostgres) {
                        if (null != schemaName) schemaName = schemaName.toLowerCase();
                        table_name = table_name.toLowerCase();
                    }

                    if(schemaName == null && (isDerby || isOracle)) {
                        ResultSet schemas = metadata.getSchemas();
                        String username = metadata.getUserName();
                        while(schemas.next()) {
                            if(schemas.getString(1).equalsIgnoreCase(username)) {
                                schemaName = schemas.getString(1);
                                break;
                            }
                        }
                        close(schemas);
                    }

                    RubyArray matchingTables = (RubyArray) tableLookupBlock(context.getRuntime(),
                                                                            c.getCatalog(), schemaName, table_name, new String[]{"TABLE","VIEW"}).call(c);
                    if (matchingTables.isEmpty()) {
                        throw new SQLException("Table " + table_name + " does not exist");
                    }

                    results = metadata.getColumns(c.getCatalog(),schemaName,table_name,null);
                    return unmarshal_columns(context, recv, metadata, results);
                } finally {
                    close(results);
                }
            }
        });
    }

    private static final java.util.regex.Pattern HAS_SMALL = java.util.regex.Pattern.compile("[a-z]");
    private static IRubyObject unmarshal_columns(ThreadContext context, IRubyObject recv,
            DatabaseMetaData metadata, ResultSet rs) throws SQLException {
        try {
            List columns = new ArrayList();
            String clzName = metadata.getClass().getName().toLowerCase();
            boolean isDerby = clzName.indexOf("derby") != -1;
            boolean isOracle = clzName.indexOf("oracle") != -1 || clzName.indexOf("oci") != -1;
            Ruby runtime = context.getRuntime();

            IRubyObject adapter = rubyApi.callMethod(recv, "adapter");
            RubyHash tps = (RubyHash) rubyApi.callMethod(adapter, "native_database_types");

            IRubyObject jdbcCol = ((RubyModule)(runtime.getModule("ActiveRecord").getConstant("ConnectionAdapters"))).getConstant("JdbcColumn");

            while(rs.next()) {
                String column_name = rs.getString(4);
                if(metadata.storesUpperCaseIdentifiers() && !HAS_SMALL.matcher(column_name).find()) {
                    column_name = column_name.toLowerCase();
                }

                String prec = rs.getString(7);
                String scal = rs.getString(9);
                int precision = -1;
                int scale = -1;
                if(prec != null) {
                    precision = Integer.parseInt(prec);
                    if(scal != null) {
                        scale = Integer.parseInt(scal);
                    }
                    else if(isOracle && rs.getInt(5) == java.sql.Types.DECIMAL) { // NUMBER type in Oracle
                        prec = null;
                    }
                }
                String type = rs.getString(6);
                if(prec != null && precision > 0) {
                    type += "(" + precision;
                    if(scal != null && scale > 0) {
                        type += "," + scale;
                    }
                    type += ")";
                }
                String def = rs.getString(13);
                IRubyObject _def;
                if(def == null || (isOracle && def.toLowerCase().trim().equals("null"))) {
                    _def = runtime.getNil();
                } else {
                    if(isOracle) {
                        def = def.trim();
                    }
                    if((isDerby || isOracle) && def.length() > 0 && def.charAt(0) == '\'') {
                        def = def.substring(1, def.length()-1);
                    }
                    _def = RubyString.newUnicodeString(runtime, def);
                }
                IRubyObject config = rubyApi.getInstanceVariable(recv, "@config");
                IRubyObject c = rubyApi.callMethod(jdbcCol, "new",
                        new IRubyObject[]{
                                                       config, RubyString.newUnicodeString(runtime, column_name),
                                                       _def, RubyString.newUnicodeString(runtime, type),
                            runtime.newBoolean(!rs.getString(18).trim().equals("NO"))
                        });
                columns.add(c);

                IRubyObject tp = (IRubyObject)tps.fastARef(rubyApi.callMethod(c,"type"));
                if(tp != null && !tp.isNil() && rubyApi.callMethod(tp, "[]", runtime.newSymbol("limit")).isNil()) {
                    rubyApi.callMethod(c, "limit=", runtime.getNil());
                    if(!rubyApi.callMethod(c, "type").equals(runtime.newSymbol("decimal"))) {
                        rubyApi.callMethod(c, "precision=", runtime.getNil());
                    }
                }
            }
            return runtime.newArray(columns);
        } finally {
            close(rs);
        }
    }

    @JRubyMethod(name = "primary_keys", required = 1)
    public static IRubyObject primary_keys(final ThreadContext context, final IRubyObject recv,
            final IRubyObject _table_name) throws SQLException {
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                DatabaseMetaData metadata = c.getMetaData();
                boolean isPostgres = metadata.getDatabaseProductName().equals("PostgreSQL");
                String table_name = _table_name.toString();
                if (metadata.storesUpperCaseIdentifiers()) {
                    table_name = table_name.toUpperCase();
                } else if (metadata.storesLowerCaseIdentifiers() && ! isPostgres) {
                    table_name = table_name.toLowerCase();
                }

                Ruby runtime = context.getRuntime();
                ResultSet result_set = null;
                List keyNames = new ArrayList();
                try {
                    result_set = metadata.getPrimaryKeys(null, null, table_name);

                    while (result_set.next()) {
                        String s1 = result_set.getString(4);
                        if (metadata.storesUpperCaseIdentifiers() && !HAS_SMALL.matcher(s1).find()) {
                            s1 = s1.toLowerCase();
                        }
                        keyNames.add(RubyString.newUnicodeString(runtime, s1));
                    }
                } finally {
                    close(result_set);
                }

                return runtime.newArray(keyNames);
            }
        });
    }

    @JRubyMethod(name = "execute_id_insert", required = 2)
    public static IRubyObject execute_id_insert(ThreadContext context, IRubyObject recv,
            final IRubyObject sql, final IRubyObject id) throws SQLException {
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                PreparedStatement ps = c.prepareStatement(rubyApi.convertToRubyString(sql).getUnicodeValue());
                try {
                    ps.setLong(1, RubyNumeric.fix2long(id));
                    ps.executeUpdate();
                } finally {
                    close(ps);
                }
                return id;
            }
        });
    }

    @JRubyMethod(name = "execute_update", required = 1)
    public static IRubyObject execute_update(final ThreadContext context, final IRubyObject recv,
            final IRubyObject sql) throws SQLException {
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                Statement stmt = null;
                try {
                    stmt = c.createStatement();
                    return context.getRuntime().newFixnum((long)stmt.executeUpdate(rubyApi.convertToRubyString(sql).getUnicodeValue()));
                } finally {
                    close(stmt);
                }
            }
        });
    }

    @JRubyMethod(name = "execute_query", rest = true)
    public static IRubyObject execute_query(final ThreadContext context, final IRubyObject recv, IRubyObject[] args) throws SQLException, IOException {
        final IRubyObject sql = args[0];
        final int maxrows = args.length > 1 ? RubyNumeric.fix2int(args[1]) : 0;
        
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                Statement stmt = null;
                try {
                    stmt = c.createStatement();
                    stmt.setMaxRows(maxrows);
                    return unmarshalResult(context, stmt.executeQuery(rubyApi.convertToRubyString(sql).getUnicodeValue()), false);
                } finally {
                    close(stmt);
                }
            }
        });
    }

    @JRubyMethod(name = "execute_insert", required = 1)
    public static IRubyObject execute_insert(final ThreadContext context, final IRubyObject recv, final IRubyObject sql) throws SQLException {
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                Statement stmt = null;
                try {
                    stmt = c.createStatement();
                    stmt.executeUpdate(rubyApi.convertToRubyString(sql).getUnicodeValue(), Statement.RETURN_GENERATED_KEYS);
                    return unmarshal_id_result(context.getRuntime(), stmt.getGeneratedKeys());
                } finally {
                    close(stmt);
                }
            }
        });
    }

    /**
     * Converts a jdbc resultset into an array (rows) of hashes (row) that AR expects.
     *
     * @param downCase should column names only be in lower case?
     */
    protected static IRubyObject unmarshalResult(ThreadContext context, ResultSet resultSet,
            boolean downCase) throws SQLException {
        Ruby runtime = context.getRuntime();
        List results = new ArrayList();
        
        try {
            boolean storesUpper = !downCase && resultSet.getStatement().getConnection().getMetaData().storesUpperCaseIdentifiers();
            ColumnData[] columns = ColumnData.setup(runtime, resultSet.getMetaData(), storesUpper);

            populateFromResultSet(context, runtime, results, resultSet, columns);
        } finally {
            close(resultSet);
        }
        
        return runtime.newArray(results);
    }

    public static class ColumnData {
        public IRubyObject name;
        public int type;

        public ColumnData(IRubyObject name, int type) {
            this.name = name;
            this.type = type;
        }

        public static ColumnData[] setup(Ruby runtime, ResultSetMetaData metadata,
                boolean storesUpper) throws SQLException {
            int columnsCount = metadata.getColumnCount();
            ColumnData[] columns = new ColumnData[columnsCount];

            for (int i = 1; i <= columnsCount; i++) { // metadata is one-based
                String name = metadata.getColumnLabel(i);
                // We don't want to lowercase mixed case columns
                if (!storesUpper || (storesUpper && !HAS_SMALL.matcher(name).find())) name = name.toLowerCase();

                columns[i - 1] = new ColumnData(RubyString.newUnicodeString(runtime, name),
                        metadata.getColumnType(i));
            }

            return columns;
        }
    }

    private static void populateFromResultSet(ThreadContext context, Ruby runtime, List results,
            ResultSet resultSet, ColumnData[] columns) throws SQLException {
        int columnCount = columns.length;

        while (resultSet.next()) {
            RubyHash row = RubyHash.newHash(runtime);

            for (int i = 0; i < columnCount; i++) {
                row.op_aset(context, columns[i].name, jdbcToRuby(runtime, i + 1, columns[i].type, resultSet));
            }
            results.add(row);
        }
    }

    private static IRubyObject streamToRuby(Ruby runtime, ResultSet resultSet, InputStream is)
            throws SQLException, IOException {
        if (is == null || resultSet.wasNull()) return runtime.getNil();

        ByteList str = new ByteList(2048);
        try {
            byte[] buf = new byte[2048];

            for (int n = is.read(buf); n != -1; n = is.read(buf)) {
                str.append(buf, 0, n);
            }
        } finally {
            is.close();
        }

        return runtime.newString(str);
    }

    private static IRubyObject readerToRuby(Ruby runtime, ResultSet resultSet, Reader reader)
            throws SQLException, IOException {
        if (reader == null || resultSet.wasNull()) return runtime.getNil();

        StringBuffer str = new StringBuffer(2048);
        try {
            char[] buf = new char[2048];

            for (int n = reader.read(buf); n != -1; n = reader.read(buf)) {
                str.append(buf, 0, n);
            }
        } finally {
            reader.close();
        }

        return RubyString.newUnicodeString(runtime, str.toString());
    }

    private static IRubyObject timestampToRuby(Ruby runtime, ResultSet resultSet, Timestamp time)
            throws SQLException, IOException {
        if (time == null || resultSet.wasNull()) return runtime.getNil();

        String str = time.toString();
        if (str.endsWith(" 00:00:00.0")) {
            str = str.substring(0, str.length() - (" 00:00:00.0".length()));
        }
        
        return RubyString.newUnicodeString(runtime, str);
    }

    private static IRubyObject stringToRuby(Ruby runtime, ResultSet resultSet, String string)
            throws SQLException, IOException {
        if (string == null || resultSet.wasNull()) return runtime.getNil();

        return RubyString.newUnicodeString(runtime, string);
    }

    private static IRubyObject jdbcToRuby(Ruby runtime, int column, int type, ResultSet resultSet)
            throws SQLException {
        try {
            switch (type) {
                case Types.BINARY: case Types.BLOB: case Types.LONGVARBINARY: case Types.VARBINARY:
                    return streamToRuby(runtime, resultSet, resultSet.getBinaryStream(column));
                case Types.LONGVARCHAR: case Types.CLOB:
                    return readerToRuby(runtime, resultSet, resultSet.getCharacterStream(column));
                case Types.TIMESTAMP:
                    return timestampToRuby(runtime, resultSet, resultSet.getTimestamp(column));
                default:
                    return stringToRuby(runtime, resultSet, resultSet.getString(column));
            }
        } catch (IOException ioe) {
            throw (SQLException) new SQLException(ioe.getMessage()).initCause(ioe);
        }
    }

    public static IRubyObject unmarshal_id_result(Ruby runtime, ResultSet rs) throws SQLException {
        try {
            if (rs.next() && rs.getMetaData().getColumnCount() > 0) {
                return runtime.newFixnum(rs.getLong(1));
            }
            return runtime.getNil();
        } finally {
            close(rs);
        }
    }

    private static String convertToStringOrNull(IRubyObject obj) {
        return obj.isNil() ? null : obj.toString();
    }

    private static int getTypeValueFor(Ruby runtime, IRubyObject type) throws SQLException {
        // How could this ever yield anything useful?
        if (!(type instanceof RubySymbol)) type = rubyApi.callMethod(type, "class");

        // Assumption; If this is a symbol then it will be backed by an interned string. (enebo)
        String internedValue = type.asJavaString();

        if(internedValue == "string") {
            return Types.VARCHAR;
        } else if(internedValue == "text") {
            return Types.CLOB;
        } else if(internedValue == "integer") {
            return Types.INTEGER;
        } else if(internedValue == "decimal") {
            return Types.DECIMAL;
        } else if(internedValue == "float") {
            return Types.FLOAT;
        } else if(internedValue == "datetime") {
            return Types.TIMESTAMP;
        } else if(internedValue == "timestamp") {
            return Types.TIMESTAMP;
        } else if(internedValue == "time") {
            return Types.TIME;
        } else if(internedValue == "date") {
            return Types.DATE;
        } else if(internedValue == "binary") {
            return Types.BLOB;
        } else if(internedValue == "boolean") {
            return Types.BOOLEAN;
        } else {
            return -1;
        }
    }

    private final static DateFormat FORMAT = new SimpleDateFormat("%y-%M-%d %H:%m:%s");

    private static void setValue(PreparedStatement ps, int index, Ruby runtime, ThreadContext context,
            IRubyObject value, IRubyObject type) throws SQLException {
        final int tp = getTypeValueFor(runtime, type);
        if(value.isNil()) {
            ps.setNull(index, tp);
            return;
        }

        switch(tp) {
        case Types.VARCHAR:
        case Types.CLOB:
            ps.setString(index, RubyString.objAsString(context, value).toString());
            break;
        case Types.INTEGER:
            ps.setLong(index, RubyNumeric.fix2long(value));
            break;
        case Types.FLOAT:
            ps.setDouble(index, ((RubyNumeric)value).getDoubleValue());
            break;
        case Types.TIMESTAMP:
        case Types.TIME:
        case Types.DATE:
            if(!(value instanceof RubyTime)) {
                try {
                    Date dd = FORMAT.parse(RubyString.objAsString(context, value).toString());
                    ps.setTimestamp(index, new java.sql.Timestamp(dd.getTime()), Calendar.getInstance());
                } catch(Exception e) {
                    ps.setString(index, RubyString.objAsString(context, value).toString());
                }
            } else {
                RubyTime rubyTime = (RubyTime) value;
                java.util.Date date = rubyTime.getJavaDate();
                long millis = date.getTime();
                long micros = rubyTime.microseconds() - millis / 1000;
                java.sql.Timestamp ts = new java.sql.Timestamp(millis);
                java.util.Calendar cal = Calendar.getInstance();
                cal.setTime(date);
                ts.setNanos((int)(micros * 1000));
                ps.setTimestamp(index, ts, cal);
            }
            break;
        case Types.BOOLEAN:
            ps.setBoolean(index, value.isTrue());
            break;
        default: throw new RuntimeException("type " + type + " not supported in _bind yet");
        }
    }

    private static void setValuesOnPS(PreparedStatement ps, Ruby runtime, ThreadContext context,
            IRubyObject values, IRubyObject types) throws SQLException {
        RubyArray vals = (RubyArray)values;
        RubyArray tps = (RubyArray)types;

        for(int i=0, j=vals.getLength(); i<j; i++) {
            setValue(ps, i+1, runtime, context, vals.eltInternal(i), tps.eltInternal(i));
        }
    }

    /*
     * sql, values, types, name = nil, pk = nil, id_value = nil, sequence_name = nil
     */
    @JRubyMethod(name = "insert_bind", required = 3, rest = true)
    public static IRubyObject insert_bind(final ThreadContext context, IRubyObject recv, final IRubyObject[] args) throws SQLException {
        final Ruby runtime = context.getRuntime();
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                PreparedStatement ps = null;
                try {
                    ps = c.prepareStatement(rubyApi.convertToRubyString(args[0]).toString(), Statement.RETURN_GENERATED_KEYS);
                    setValuesOnPS(ps, runtime, context, args[1], args[2]);
                    ps.executeUpdate();
                    return unmarshal_id_result(runtime, ps.getGeneratedKeys());
                } finally {
                    close(ps);
                }
            }
        });
    }

    /*
     * sql, values, types, name = nil
     */
    @JRubyMethod(name = "update_bind", required = 3, rest = true)
    public static IRubyObject update_bind(final ThreadContext context, IRubyObject recv, final IRubyObject[] args) throws SQLException {
        final Ruby runtime = context.getRuntime();
        Arity.checkArgumentCount(runtime, args, 3, 4);
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                PreparedStatement ps = null;
                try {
                    ps = c.prepareStatement(rubyApi.convertToRubyString(args[0]).toString());
                    setValuesOnPS(ps, runtime, context, args[1], args[2]);
                    ps.executeUpdate();
                } finally {
                    close(ps);
                }
                return runtime.getNil();
            }
        });
    }

    /*
     * (is binary?, colname, tablename, primary key, id, value)
     */
    @JRubyMethod(name = "write_large_object", required = 6)
    public static IRubyObject write_large_object(ThreadContext context, IRubyObject recv, final IRubyObject[] args)
            throws SQLException, IOException {
        final Ruby runtime = context.getRuntime();
        return withConnectionAndRetry(context, recv, new SQLBlock() {
            public IRubyObject call(Connection c) throws SQLException {
                String sql = "UPDATE " + rubyApi.convertToRubyString(args[2])
                        + " SET " + rubyApi.convertToRubyString(args[1])
                        + " = ? WHERE " + rubyApi.convertToRubyString(args[3])
                        + "=" + rubyApi.convertToRubyString(args[4]);
                PreparedStatement ps = null;
                try {
                    ps = c.prepareStatement(sql);
                    if (args[0].isTrue()) { // binary
                        ByteList outp = rubyApi.convertToRubyString(args[5]).getByteList();
                        ps.setBinaryStream(1, new ByteArrayInputStream(outp.bytes,
                                outp.begin, outp.realSize), outp.realSize);
                    } else { // clob
                        String ss = rubyApi.convertToRubyString(args[5]).getUnicodeValue();
                        ps.setCharacterStream(1, new StringReader(ss), ss.length());
                    }
                    ps.executeUpdate();
                } finally {
                    close(ps);
                }
                return runtime.getNil();
            }
        });
    }

    private static Connection getConnection(IRubyObject recv) {
        return getConnection(recv, false);
    }

    private static Connection getConnection(IRubyObject recv, boolean error) {
        Connection conn = (Connection) recv.dataGetStruct();
        if(error && conn == null) {
            RubyClass err = recv.getRuntime().getModule("ActiveRecord").getClass("ConnectionNotEstablished");
            throw new RaiseException(recv.getRuntime(), err, "no connection available", false);
        }
        return conn;
    }

    private static RuntimeException wrap(IRubyObject recv, Throwable exception) {
        RubyClass err = recv.getRuntime().getModule("ActiveRecord").getClass("ActiveRecordError");
        return (RuntimeException) new RaiseException(recv.getRuntime(), err, exception.getMessage(), false).initCause(exception);
    }

    private static boolean isConnectionBroken(ThreadContext context, IRubyObject recv, Connection c) {
        try {
            IRubyObject alive = config_value(recv, "connection_alive_sql");
            if (select_p(context, recv, alive).isTrue()) {
                String connectionSQL = rubyApi.convertToRubyString(alive).toString();
                Statement s = c.createStatement();
                try {
                    s.execute(connectionSQL);
                } finally {
                    close(s);
                }
                return false;
            } else {
                return !c.isClosed();
            }
        } catch (SQLException sx) {
            return true;
        }
    }

    private static IRubyObject setConnection(IRubyObject recv, Connection c) {
        Connection prev = getConnection(recv);
        if (prev != null) {
            try {
                prev.close();
            } catch(Exception e) {}
        }
        IRubyObject rubyconn = recv.getRuntime().getNil();
        if (c != null) {
            rubyconn = wrappedConnection(recv,c);
        }
        rubyApi.setInstanceVariable(recv, "@connection", rubyconn);
        recv.dataWrapStruct(c);
        return recv;
    }

    private static IRubyObject wrappedConnection(IRubyObject recv, Connection c) {
        return Java.java_to_ruby(recv, JavaObject.wrap(recv.getRuntime(), c), Block.NULL_BLOCK);
    }

    private static JdbcConnectionFactory getConnectionFactory(IRubyObject recv) throws RaiseException {
        IRubyObject connection_factory = rubyApi.getInstanceVariable(recv, "@connection_factory");
        JdbcConnectionFactory factory = null;
        try {
            factory = (JdbcConnectionFactory) ((JavaObject) rubyApi.getInstanceVariable(connection_factory, "@java_object")).getValue();
        } catch (Exception e) {
            factory = null;
        }
        if (factory == null) {
            throw recv.getRuntime().newRuntimeError("@connection_factory not set properly");
        }
        return factory;
    }

    private static IRubyObject config_value(IRubyObject recv, String key) {
        Ruby runtime = recv.getRuntime();
        IRubyObject config_hash = rubyApi.getInstanceVariable(recv, "@config");
        return rubyApi.callMethod(config_hash, "[]", runtime.newSymbol(key));
    }

    public static void close(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch(Exception e) {}
        }
    }

    public static void close(ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch(Exception e) {}
        }
    }
}
