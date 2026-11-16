package com.saferoute.testsupport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DB 없이 저장소(Repository)·적재 코드를 돌려 보는 가짜 JDBC.
 * 진짜 {@link JdbcTemplate} 아래에 가짜 연결을 붙여서, 실행한 SQL과 넣은 값을 {@link #calls}에 남기고
 * SQL에 들어 있는 글자로 정해 둔 줄({@link #rows})이나 바뀐 줄 수({@link #updates})를 돌려준다.
 * 정해 두지 않은 SELECT 는 빈 결과, UPDATE·INSERT·DELETE 는 1줄을 돌려준다.
 */
public final class FakeDb {

    /** 실행한 SQL 하나와 넣은 값 (배치는 줄마다 하나) */
    public record Call(String sql, List<Object> params) {
    }

    private record Rule(String part, List<Map<String, Object>> rows, Integer count, SQLException error) {
    }

    public final List<Call> calls = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private Object generatedKey;

    /** SQL에 part 가 들어 있으면 이 줄들을 돌려준다 (먼저 정한 것이 먼저) */
    @SafeVarargs
    public final FakeDb rows(String part, Map<String, Object>... rows) {
        rules.add(new Rule(part, List.of(rows), null, null));
        return this;
    }

    /** SQL에 part 가 들어 있으면 바뀐 줄 수로 n 을 돌려준다 */
    public FakeDb updates(String part, int n) {
        rules.add(new Rule(part, null, n, null));
        return this;
    }

    /** SQL에 part 가 들어 있으면 이 오류를 낸다 (표·칸이 없을 때 등) */
    public FakeDb fails(String part, String message) {
        rules.add(new Rule(part, null, null, new SQLException(message, "42S22", 1054)));
        return this;
    }

    /** INSERT 뒤 돌려줄 새 번호 */
    public FakeDb generatedKey(Object key) {
        this.generatedKey = key;
        return this;
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(proxy(DataSource.class, (p, m, a) -> switch (m.getName()) {
            case "getConnection" -> connection();
            case "isWrapperFor" -> false;
            default -> defaultValue(m.getReturnType());
        }));
    }

    /** part 가 들어 있는 SQL 실행만 */
    public List<Call> calls(String part) {
        return calls.stream().filter(c -> c.sql().contains(part)).toList();
    }

    /** part 가 들어 있는 SQL 실행 하나 (없거나 여러 개면 실패) */
    public Call call(String part) {
        List<Call> found = calls(part);
        if (found.size() != 1) {
            throw new AssertionError("'" + part + "' SQL이 " + found.size() + "번 실행됨: " + calls);
        }
        return found.get(0);
    }

    /** 칸 이름과 값을 번갈아 받아 한 줄을 만든다. 순서가 칸 번호(1부터)가 된다 */
    public static Map<String, Object> row(Object... nameValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < nameValue.length; i += 2) {
            m.put((String) nameValue[i], nameValue[i + 1]);
        }
        return m;
    }

    // ---------- 가짜 연결 ----------

    private Connection connection() {
        return proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
            case "prepareStatement" -> statement((String) a[0]);
            case "createStatement" -> statement(null);
            case "getMetaData" -> proxy(DatabaseMetaData.class, (p2, m2, a2) -> switch (m2.getName()) {
                case "supportsBatchUpdates" -> true;
                case "getDatabaseProductName" -> "MySQL";
                case "getDriverName" -> "MySQL Connector/J";
                default -> defaultValue(m2.getReturnType());
            });
            case "getAutoCommit" -> true;
            default -> defaultValue(m.getReturnType());
        });
    }

    private PreparedStatement statement(String preparedSql) {
        Map<Integer, Object> params = new TreeMap<>();
        String[] sql = {preparedSql};
        ResultSet[] keys = {null};
        return proxy(PreparedStatement.class, (p, m, a) -> {
            String name = m.getName();
            if (name.startsWith("set") && a != null && a.length >= 2 && a[0] instanceof Integer i
                    && !name.equals("setFetchSize") && !name.equals("setMaxRows") && !name.equals("setQueryTimeout")) {
                params.put(i, name.equals("setNull") ? null : a[1]);
                return null;
            }
            switch (name) {
                case "executeQuery": {
                    if (a != null && a.length > 0) {
                        sql[0] = (String) a[0];
                    }
                    Rule r = record(sql[0], params);
                    return resultSet(r == null || r.rows() == null ? List.of() : r.rows());
                }
                case "executeUpdate": {
                    if (a != null && a.length > 0) {
                        sql[0] = (String) a[0];
                    }
                    Rule r = record(sql[0], params);
                    if (generatedKey != null) {
                        keys[0] = resultSet(List.of(row("GENERATED_KEY", generatedKey)));
                    }
                    return r == null || r.count() == null ? 1 : r.count();
                }
                case "execute": {
                    if (a != null && a.length > 0) {
                        sql[0] = (String) a[0];
                    }
                    record(sql[0], params);
                    return false;
                }
                case "addBatch":
                    record(sql[0], params);
                    params.clear();
                    return null;
                case "executeBatch": {
                    long n = calls.stream().filter(c -> c.sql().equals(sql[0])).count();
                    int[] out = new int[(int) n];
                    java.util.Arrays.fill(out, 1);
                    return out;
                }
                case "getGeneratedKeys":
                    return keys[0] != null ? keys[0] : resultSet(List.of());
                case "getParameterMetaData":
                    return proxy(ParameterMetaData.class, (p2, m2, a2) ->
                            m2.getName().equals("getParameterType") ? Types.VARCHAR : defaultValue(m2.getReturnType()));
                case "getUpdateCount":
                    return -1;
                case "getConnection":
                    return connection();
                default:
                    return defaultValue(m.getReturnType());
            }
        });
    }

    private Rule record(String sql, Map<Integer, Object> params) throws SQLException {
        calls.add(new Call(sql, new ArrayList<>(params.values())));
        for (Rule r : rules) {
            if (sql != null && sql.contains(r.part())) {
                if (r.error() != null) {
                    throw r.error();
                }
                return r;
            }
        }
        return null;
    }

    // ---------- 가짜 결과 ----------

    static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] at = {-1};
        boolean[] lastNull = {false};
        List<String> cols = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        ResultSetMetaData meta = proxy(ResultSetMetaData.class, (p, m, a) -> switch (m.getName()) {
            case "getColumnCount" -> cols.size();
            case "getColumnLabel", "getColumnName" -> cols.get((Integer) a[0] - 1);
            case "getColumnType" -> Types.OTHER;
            default -> defaultValue(m.getReturnType());
        });
        return proxy(ResultSet.class, (p, m, a) -> {
            String name = m.getName();
            switch (name) {
                case "next":
                    return ++at[0] < rows.size();
                case "wasNull":
                    return lastNull[0];
                case "getMetaData":
                    return meta;
                case "findColumn":
                    return cols.indexOf((String) a[0]) + 1;
                case "close", "isClosed":
                    return defaultValue(m.getReturnType());
                default:
                    break;
            }
            if (name.startsWith("get") && a != null && a.length >= 1) {
                Map<String, Object> row = rows.get(at[0]);
                Object v;
                if (a[0] instanceof Integer i) {
                    v = new ArrayList<>(row.values()).get(i - 1);
                } else {
                    String col = (String) a[0];
                    if (!row.containsKey(col)) {
                        throw new SQLException("칸 없음: " + col);
                    }
                    v = row.get(col);
                }
                lastNull[0] = v == null;
                Class<?> want = a.length >= 2 && a[1] instanceof Class<?> c ? c : null;
                return convert(v, name, want);
            }
            return defaultValue(m.getReturnType());
        });
    }

    private static Object convert(Object v, String getter, Class<?> want) {
        String type = want != null ? want.getSimpleName() : getter.substring(3);
        if (v == null) {
            if (want != null) {
                return null; // getObject(칸, Integer.class) 는 null 을 그대로
            }
            return switch (type) {
                case "Int", "Long", "Short", "Byte" -> 0;
                case "Double", "Float" -> 0.0;
                case "Boolean" -> false;
                default -> null;
            };
        }
        return switch (type) {
            case "String" -> String.valueOf(v);
            case "Int", "Integer" -> ((Number) v).intValue();
            case "Long" -> ((Number) v).longValue();
            case "Short" -> ((Number) v).shortValue();
            case "Double" -> ((Number) v).doubleValue();
            case "Float" -> ((Number) v).floatValue();
            case "BigDecimal" -> new BigDecimal(String.valueOf(v));
            case "Boolean" -> v instanceof Boolean b ? b : ((Number) v).intValue() != 0;
            case "Date" -> v instanceof LocalDate d ? Date.valueOf(d) : v;
            case "Time" -> v instanceof LocalTime t ? Time.valueOf(t) : v;
            case "Timestamp" -> v instanceof LocalDateTime t ? Timestamp.valueOf(t) : v;
            default -> v;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(FakeDb.class.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> type.getSimpleName() + "(가짜)";
                };
            }
            if (m.getName().equals("unwrap")) {
                return null;
            }
            return h.invoke(p, m, a);
        });
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive() || t == void.class) {
            return null;
        }
        if (t == boolean.class) {
            return false;
        }
        if (t == int.class) {
            return 0;
        }
        if (t == long.class) {
            return 0L;
        }
        if (t == double.class) {
            return 0.0;
        }
        if (t == float.class) {
            return 0f;
        }
        if (t == short.class) {
            return (short) 0;
        }
        if (t == byte.class) {
            return (byte) 0;
        }
        return null;
    }
}
