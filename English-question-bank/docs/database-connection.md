# 数据库连接实现说明（Spring Boot 4 + MySQL + MyBatis-Plus）

> 本文记录本项目「从零接通数据库」的完整实现与操作步骤。
> 换新项目时照着第二节的清单做即可。

---

## 一、整体链路：四个环节

连接数据库不是魔法，是一条清晰的链：

```
① 依赖          ② 配置                 ③ 自动装配               ④ 使用/验证
   ↓               ↓                      ↓                        ↓
starter      spring.datasource.*    DataSourceAutoConfiguration   注入 DataSource
  jar        (yml / properties)              ↓                    Mapper / JdbcTemplate
                                          HikariCP 连接池          /actuator/health
```

**核心认知**：你从来没有写过一行「连接 MySQL」的代码。
你只是**声明了依赖**和**填了配置**，剩下的是 Spring Boot 的自动装配完成的。

---

## 二、环节 ①：依赖层 —— 三个 jar 各司其职

```xml
<!-- 1. JDBC 驱动：负责"会说 MySQL 协议" -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- 2. JDBC 抽象 + 连接池 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>

<!-- 3. ORM（可选）：Mapper 接口 -> SQL -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
    <version>3.5.16</version>
</dependency>
```

### 为什么 `mysql-connector-j` 的 scope 是 `runtime`？

因为**编译期根本用不到它**。Java 代码里只出现 JDBC 标准接口（`java.sql.Connection`、
`javax.sql.DataSource`），这些都来自 JDK。MySQL 的具体实现类只有在运行时
才需要被加载。写成 `runtime` 能让编译期类路径更干净。

### 最容易搞错的一点

**只有驱动，Spring 不会自动建连接池。**

- `mysql-connector-j` = 只会说 MySQL 方言的「翻译官」
- `spring-boot-starter-jdbc` = 提供 `DataSource` 抽象 + HikariCP 连接池

少了第 2 个，应用能编译，但启动时不会创建任何 DataSource，注入时报
`NoSuchBeanDefinitionException`。

### MyBatis-Plus 在 Spring Boot 4 下的两个硬性要求

| 要求 | 说明 |
|---|---|
| 坐标必须是 `mybatis-plus-spring-boot4-starter` | 名带 **boot4**，不是 `boot3` |
| 版本必须 **>= 3.5.16** | 3.5.13 才引入 Boot 4 支持，但 3.5.14/3.5.15 有缺陷（[issue #6970](https://github.com/baomidou/mybatis-plus/issues/6970)），3.5.16 才修复 |

---

## 三、环节 ②：配置层 —— 连接的全部秘密

文件：`src/main/resources/application.yml`

```yaml
spring:
  datasource:
    url: "jdbc:mysql://localhost:3306/english_question_bank?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
    username: root
    password: 你的密码
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      pool-name: EnglishQbPool
```

### 为什么写 `spring.datasource.url` 就生效了？

因为 Spring Boot 里有一个类叫 **`DataSourceProperties`**，它头上写着：

```java
@ConfigurationProperties("spring.datasource")
public class DataSourceProperties { ... }
```

`@ConfigurationProperties("spring.datasource")` 就是那个「契约」：
**yml 里以 `spring.datasource` 开头的东西，会被自动绑定到这个对象的字段上。**

> 想验证这一点：在 IDEA 里 `Ctrl + 左键` 点击 `spring.datasource.url`，
> 能直接跳到绑定它的类。这就是配置生效的真正机制，不是约定俗成的魔法。

### URL 三个必需参数（少一个就连不上）

| 参数 | 作用 | 不加的后果 |
|---|---|---|
| `characterEncoding=utf8` | 中文不乱码 | 中文变 `?` |
| `serverTimezone=Asia/Shanghai` | 指定时区 | 报时区无法识别 |
| `allowPublicKeyRetrieval=true` | 允许索取公钥 | 报 `Public Key Retrieval is not allowed` |
| `useSSL=false` | 本地开发不走 SSL | MySQL 8 默认要求 SSL，可能连接失败 |

**为什么 `allowPublicKeyRetrieval` 是最高频的坑**：
MySQL 8 默认认证插件是 `caching_sha2_password`，它比老版 `mysql_native_password`
更安全。在非 SSL 连接下，客户端首次需要向服务器索取 RSA 公钥来加密密码——
默认被禁止，必须显式打开。

### 一个 YAML 陷阱

URL 用**双引号**包起来。因为 YAML 里 `&` 出现在标量开头有特殊含义（锚点引用），
`:` 加空格是键值分隔符。这个 URL 恰好都不触发，但引起来最稳。

### `.properties` 与 `.yml` 的优先级（会静默坑死你）

> **两个文件同时存在时，Spring Boot 优先读 `.properties`，
> `.yml` 里的同名配置被静默忽略——不报错、不警告。**

本项目已删除 `application.properties`，配置**只**放在 `application.yml`。

---

## 四、环节 ③：自动装配 —— Spring Boot 到底做了什么

启动时 `DataSourceAutoConfiguration` 会判断：

```
classpath 上有 DataSource 类吗？            → 有（starter-jdbc 带来的）
容器里已经有自定义 DataSource Bean 吗？      → 没有
                    ↓
          创建一个 DataSource Bean
                    ↓
classpath 上有 HikariCP 吗？               → 有 → 用 HikariDataSource
                    ↓
从 spring.datasource.* 绑定 url/username/password
                    ↓
从 url 推断驱动类（driver-class-name 其实可以不写）
```

### 所以：不要手写这些代码

```java
// ❌ 千万不要这样写——Spring Boot 已经替你做了
@Bean
public DataSource dataSource() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:mysql://...");
    return new HikariDataSource(config);
}
```

一旦你写了自定义 `DataSource` Bean，自动装配会因 `@ConditionalOnMissingBean` 而**退让**，
你的硬编码配置就会覆盖掉 yml——这是新手常见的「改了 yml 没反应」的原因之一。

---

## 五、环节 ④：如何判断连上了 —— 三层验证

### 第 ① 层：启动日志（最直观）

```
com.zaxxer.hikari.HikariDataSource : EnglishQbPool - Starting...
com.zaxxer.hikari.pool.HikariPool  : EnglishQbPool - Added connection com.mysql.cj.jdbc.ConnectionImpl@28f05b0c
com.zaxxer.hikari.HikariDataSource : EnglishQbPool - Start completed.
```

- `Starting...` 只说明连接池**开始**初始化，**不代表连上了**
- `Added connection ...` 才是真正拿到了一条数据库连接
- `Start completed.` 表示池子就绪

### 第 ② 层：启动自检类（信息最全，推荐）

`src/main/java/org/example/englishquestionbank/config/DatabaseConnectionChecker.java`

实现 `CommandLineRunner` 接口，Spring Boot 会在启动完成后自动调用一次。
它分四层递进验证：

| 层次 | 验证内容 | 为什么需要 |
|---|---|---|
| `[1/4]` | 从连接池借出一个 `Connection` | 验证账号密码 / 网络 / 库名 |
| `[2/4]` | 读元数据（产品名、版本、地址、当前库） | 验证连的是**你以为的那个库** |
| `[3/4]` | 真的执行 `SELECT 1` | **能建连接 ≠ 语句能跑** |
| `[4/4]` | 查 `information_schema` 列出所有表 | 验证表存在、读得到 |

第 ④ 层最实用：直接列出表名，一眼看出表建没建、库选没选错。

### 第 ③ 层：Actuator 健康检查（最权威，可做监控）

```
http://localhost:8080/actuator/health
```

成功：
```json
{"status":"UP","components":{"db":{"status":"UP","details":{"database":"MySQL","validationQuery":"isValid()"}}}}
```

失败：**HTTP 503** + `"db":{"status":"DOWN","details":{"error":"..."}}`

> 记住这个诀窍：**`/actuator/health` 返回 200 还是 503，可以直接拿来做
> 自动化判断和监控告警**，比翻日志可靠得多。

需要这两行配置才有详细输出：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

---

## 六、从零复现的操作清单

新项目接通数据库，照这个顺序做：

### Step 1　加依赖
`pom.xml` 里加 `spring-boot-starter-jdbc` + `mysql-connector-j`（+ ORM starter）。

### Step 2　写配置
创建 `src/main/resources/application.yml`，填 `spring.datasource` 的四项。
**顺手删掉 `application.properties`**（如果存在）。

### Step 3　重新构建
```powershell
.\mvnw.cmd clean package -DskipTests
```
或在 IDEA 里 `Build → Rebuild Project`。

> ⚠ **`clean` 不是可选的**。Maven 复制资源时**不会删除**已删除的旧文件，
> `target/classes` 里会残留旧的 `application.properties`，被打进 jar 后
> 优先级高于 yml，导致你的 yml 静默失效。

### Step 4　启动并读日志
搜索关键词 `HikariPool`、`Start completed`。

### Step 5　打开健康检查
`http://localhost:8080/actuator/health` → 期望 `"status":"UP"`。

---

## 七、故障排查对照表

| 报错关键词 | 真正原因 |
|---|---|
| `Access denied for user 'x'@'localhost'` | **密码不对**（配置本身没问题） |
| `Unknown database 'xxx'` | 库名拼错，或表建在别的库里 |
| `Public Key Retrieval is not allowed` | URL 少了 `allowPublicKeyRetrieval=true` |
| `Communications link failure` | MySQL 服务没启动，或端口不对 |
| `The server time zone value ... is unrecognized` | URL 少了 `serverTimezone` |
| `NoSuchBeanDefinitionException: DataSource` | 漏了 `spring-boot-starter-jdbc` |
| `Cannot load driver class` | 驱动坐标写错，或漏了 `mysql-connector-j` |
| 改了 yml 但没生效 | ① 存在 `application.properties` 抢优先级 ② 没 `clean` 重建 ③ 自己定义了 DataSource Bean |

---

## 八、本项目实际踩过并已修复的坑

| 坑 | 现象 | 修复 |
|---|---|---|
| JDK 17 中文日志乱码 | 日志显示 `���ݿ������` | `logging.charset.console=UTF-8` |
| IDEA resources 目录编码 | 配置文件中文乱码，Java 文件正常 | `.idea/encodings.xml` 设项目级 UTF-8 |
| `.properties` 抢优先级 | yml 改了不起作用 | 删除 `application.properties` |
| Maven 不清理陈旧资源 | jar 里同时存在新旧两个配置文件 | 必须 `clean package` |
| MyBatis-Plus 版本 | Boot 4 下装配失败 | 用 `boot4-starter` 且版本 `>= 3.5.16` |
| MP 主键策略不匹配 | 插入时往自增主键塞雪花值 | `id-type: auto` |

---

## 九、相关文件索引

| 文件 | 作用 |
|---|---|
| `pom.xml` | 依赖声明 |
| `src/main/resources/application.yml` | 数据源 / Actuator / MyBatis-Plus / 日志编码 |
| `src/main/resources/db/schema.sql` | 建表脚本（11 张表） |
| `.../config/DatabaseConnectionChecker.java` | 数据库连接自检（开发期辅助，可删） |
| `.../config/MyBatisPlusCompatibilityChecker.java` | MyBatis-Plus 装配自检（开发期辅助，可删） |
| `.idea/encodings.xml` | IDEA 编码设置（项目级 UTF-8） |
