# 身份证字段扫描工具（JDK8 + Spring Boot，支持 MySQL / 达梦）

用于扫描指定 schema/database 下的文本字段，识别“疑似身份证号”数据，并输出匹配条数超过阈值（默认 20）的字段。

## 技术栈

- JDK 8
- Spring Boot 2.7.x
- Spring JDBC
- MySQL / 达梦 JDBC 驱动

## 构建

```bash
mvn clean package
```

构建产物：`target/data-check-1.0.0.jar`

## 使用示例

### MySQL

```bash
java -jar target/data-check-1.0.0.jar \
  --dbType mysql \
  --host 127.0.0.1 \
  --port 3306 \
  --user your_user \
  --password your_password \
  --schema your_db \
  --threshold 20
```

### 达梦（DM）

```bash
java -jar target/data-check-1.0.0.jar \
  --dbType dm \
  --host 127.0.0.1 \
  --port 5236 \
  --user SYSDBA \
  --password your_password \
  --schema YOUR_SCHEMA \
  --threshold 20
```

## 参数说明

- `--dbType`: 数据库类型，`mysql` 或 `dm`（默认 `mysql`）
- `--host`: 数据库地址，默认 `127.0.0.1`
- `--port`: 数据库端口，默认 `3306`
- `--user`: 数据库用户名（必填）
- `--password`: 数据库密码（必填）
- `--schema`: 目标 schema/database（必填）
- `--threshold`: 匹配阈值，默认 `20`（输出条件为 `> threshold`）

## 输出说明

- 未命中：
  - `No column found with ID-card-like values > 20 in schema 'xxx' (mysql|dm).`
- 命中：
  - 输出 `table_name`, `column_name`, `match_count`

## 身份证识别规则

当前使用 18 位身份证格式正则：

```text
^[1-9][0-9]{5}(18|19|20)[0-9]{2}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx]$
```

> 说明：这是快速识别疑似身份证字段的规则，不等价于严格身份证合法性校验。

## 驱动说明

- MySQL 驱动已在 `pom.xml` 中声明。
- 达梦驱动使用 `com.dameng:DmJdbcDriver18`。如你的内网仓库没有该坐标，请按公司规范替换为可用版本或手工安装到 Maven 私服/本地仓库。
