package com.example.datacheck;

import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class DataCheckApplication implements CommandLineRunner {

    private static final String ID_CARD_REGEX =
            "^[1-9][0-9]{5}(18|19|20)[0-9]{2}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx]$";

    private static final List<String> MYSQL_TEXT_TYPES;

    static {
        List<String> types = new ArrayList<String>();
        types.add("char");
        types.add("varchar");
        types.add("tinytext");
        types.add("text");
        types.add("mediumtext");
        types.add("longtext");
        MYSQL_TEXT_TYPES = Collections.unmodifiableList(types);
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DataCheckApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.run(args);
    }

    @Override
    public void run(String... args) {
        Args cli = Args.parse(args);
        if (cli.help) {
            System.out.println(Args.helpText());
            return;
        }

        cli.validate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(buildDataSource(cli));
        List<ColumnRef> candidateColumns = fetchCandidateColumns(jdbcTemplate, cli);
        List<ScanResult> results = new ArrayList<ScanResult>();

        for (ColumnRef column : candidateColumns) {
            try {
                long matchCount = scanColumn(jdbcTemplate, cli, column.tableName, column.columnName);
                if (matchCount > cli.threshold) {
                    results.add(new ScanResult(column.tableName, column.columnName, matchCount));
                }
            } catch (Exception ex) {
                System.out.printf("[WARN] skip %s.%s: %s%n", column.tableName, column.columnName, ex.getMessage());
            }
        }

        if (results.isEmpty()) {
            System.out.printf("No column found with ID-card-like values > %d in schema '%s' (%s).%n",
                    cli.threshold, cli.schema, cli.dbType);
            return;
        }

        System.out.printf("Columns with ID-card-like values > %d in schema '%s' (%s):%n",
                cli.threshold, cli.schema, cli.dbType);
        System.out.println("table_name\tcolumn_name\tmatch_count");

        Collections.sort(results, new Comparator<ScanResult>() {
            @Override
            public int compare(ScanResult o1, ScanResult o2) {
                return Long.compare(o2.matchCount, o1.matchCount);
            }
        });

        for (ScanResult item : results) {
            System.out.printf("%s\t%s\t%d%n", item.tableName, item.columnName, item.matchCount);
        }
    }

    private static DataSource buildDataSource(Args args) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        if ("mysql".equals(args.dbType)) {
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai",
                    args.host, args.port, args.schema
            ));
        } else {
            dataSource.setDriverClassName("dm.jdbc.driver.DmDriver");
            dataSource.setUrl(String.format("jdbc:dm://%s:%d", args.host, args.port));
        }

        dataSource.setUsername(args.user);
        dataSource.setPassword(args.password);
        return dataSource;
    }

    private static List<ColumnRef> fetchCandidateColumns(JdbcTemplate jdbcTemplate, Args args) {
        if ("mysql".equals(args.dbType)) {
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < MYSQL_TEXT_TYPES.size(); i++) {
                if (i > 0) {
                    placeholders.append(",");
                }
                placeholders.append("?");
            }

            String sql = "SELECT table_name, column_name " +
                    "FROM information_schema.columns " +
                    "WHERE table_schema = ? " +
                    "AND data_type IN (" + placeholders + ") " +
                    "ORDER BY table_name, ordinal_position";

            List<Object> params = new ArrayList<Object>();
            params.add(args.schema);
            params.addAll(MYSQL_TEXT_TYPES);

            return queryMysqlColumns(jdbcTemplate, sql, params.toArray());
        }

        String dmSql = "SELECT table_name, column_name " +
                "FROM all_tab_columns " +
                "WHERE owner = ? " +
                "AND data_type IN ('CHAR','VARCHAR','VARCHAR2','TEXT','CLOB') " +
                "ORDER BY table_name, column_id";

        return jdbcTemplate.query(dmSql,
                new Object[]{args.schema.toUpperCase()},
                (rs, rowNum) -> new ColumnRef(rs.getString("table_name"), rs.getString("column_name")));
    }

    private static List<ColumnRef> queryMysqlColumns(JdbcTemplate jdbcTemplate, String sql, Object[] params) {
        return jdbcTemplate.query(sql, params,
                (rs, rowNum) -> new ColumnRef(rs.getString("table_name"), rs.getString("column_name")));
    }

    private static long scanColumn(JdbcTemplate jdbcTemplate, Args args, String tableName, String columnName) {
        String tableExpr = quoteIdentifier(args.dbType, tableName);
        String columnExpr = quoteIdentifier(args.dbType, columnName);

        String sql;
        Object[] params;

        if ("mysql".equals(args.dbType)) {
            String schemaExpr = quoteIdentifier(args.dbType, args.schema);
            sql = "SELECT COUNT(*) FROM " + schemaExpr + "." + tableExpr + " WHERE " + columnExpr + " REGEXP ?";
            params = new Object[]{ID_CARD_REGEX};
        } else {
            String schemaExpr = quoteIdentifier(args.dbType, args.schema.toUpperCase());
            sql = "SELECT COUNT(*) FROM " + schemaExpr + "." + tableExpr + " WHERE REGEXP_LIKE(" + columnExpr + ", ?)";
            params = new Object[]{ID_CARD_REGEX};
        }

        Long result = jdbcTemplate.queryForObject(sql, params, Long.class);
        return result == null ? 0L : result.longValue();
    }

    private static String quoteIdentifier(String dbType, String identifier) {
        if ("mysql".equals(dbType)) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static class ColumnRef {
        private final String tableName;
        private final String columnName;

        private ColumnRef(String tableName, String columnName) {
            this.tableName = tableName;
            this.columnName = columnName;
        }
    }

    private static class ScanResult {
        private final String tableName;
        private final String columnName;
        private final long matchCount;

        private ScanResult(String tableName, String columnName, long matchCount) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.matchCount = matchCount;
        }
    }

    private static class Args {
        private final boolean help;
        private final String dbType;
        private final String host;
        private final int port;
        private final String user;
        private final String password;
        private final String schema;
        private final int threshold;

        private Args(Map<String, String> values, boolean help) {
            this.help = help;
            this.dbType = values.getOrDefault("dbType", "mysql").toLowerCase();
            this.host = values.getOrDefault("host", "127.0.0.1");
            this.port = Integer.parseInt(values.getOrDefault("port", "3306"));
            this.user = values.get("user");
            this.password = values.get("password");
            this.schema = values.get("schema");
            this.threshold = Integer.parseInt(values.getOrDefault("threshold", "20"));
        }

        static Args parse(String[] args) {
            Map<String, String> parsed = new HashMap<String, String>();
            boolean help = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                    continue;
                }
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Invalid option: " + arg + "\n\n" + helpText());
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for option: " + arg + "\n\n" + helpText());
                }
                parsed.put(arg.substring(2), args[++i]);
            }

            return new Args(parsed, help);
        }

        void validate() {
            if (!"mysql".equals(dbType) && !"dm".equals(dbType)) {
                throw new IllegalArgumentException("--dbType only supports mysql or dm.");
            }
            if (isBlank(user) || isBlank(password) || isBlank(schema)) {
                throw new IllegalArgumentException("--user, --password and --schema are required.\n\n" + helpText());
            }
            if (port <= 0 || threshold < 0) {
                throw new IllegalArgumentException("--port must be > 0 and --threshold must be >= 0.");
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }

        static String helpText() {
            return "Usage:\n" +
                    "  java -jar target/data-check-1.0.0.jar \\\n" +
                    "    --dbType mysql|dm \\\n" +
                    "    --host 127.0.0.1 \\\n" +
                    "    --port 3306 \\\n" +
                    "    --user your_user \\\n" +
                    "    --password your_password \\\n" +
                    "    --schema your_schema \\\n" +
                    "    --threshold 20\n\n" +
                    "Options:\n" +
                    "  --dbType      Database type: mysql or dm (default: mysql)\n" +
                    "  --host        Database host (default: 127.0.0.1)\n" +
                    "  --port        Database port (default: 3306)\n" +
                    "  --user        Database username (required)\n" +
                    "  --password    Database password (required)\n" +
                    "  --schema      Target schema/database (required)\n" +
                    "  --threshold   Match count threshold (default: 20)\n" +
                    "  --help, -h    Show this help\n";
        }
    }
}
