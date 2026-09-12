# Step 1 - Environment and Maven

## Goal

An empty but strict multi-module Maven project that compiles on JDK 21 only, treats every compiler warning as an error, and runs JUnit 5.

## Concepts

- **Maven multi-module build**: a parent `pom.xml` with `<packaging>pom</packaging>` lists modules; each module has its own `pom.xml` and inherits settings from the parent.
- **Maven reactor**: when you build from the parent, Maven builds modules in dependency order and lets one module resolve another from the same build (no `install` needed).
- **Enforcer plugin**: fails the build when a rule is violated (here: wrong Java version).
- **`-Xlint:all -Werror`**: turn on every javac lint and fail on any warning.

## Design

- Group id `dev.frierendb`, parent artifact `frierendb-parent`, version `0.1.0-SNAPSHOT`.
- Modules: `engine` (no runtime deps) and `agent` (added at step 16).
- Java `release 21` so the compiler also checks API availability.
- JUnit managed through the `junit-bom` so all JUnit artifacts share one version (5.11.4).
- Plugin versions are pinned (compiler 3.13.0, surefire 3.5.2, enforcer 3.5.0) for reproducible builds.

## Build it

### 1. Install JDK 21 and select it

macOS with Homebrew:

```bash
brew install openjdk@21
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
$JAVA_HOME/bin/java -version
```

Maven picks the JDK from `JAVA_HOME`. If your machine's default JDK is newer, the enforcer will reject it, which is intended.

### 2. Create the layout

```
frierendb/
  pom.xml
  .gitignore
  engine/
    pom.xml
    src/main/java/dev/frierendb/engine/
    src/test/java/dev/frierendb/engine/
```

`.gitignore` inside `frierendb/`:

```
target/
*.class
.idea/
*.iml
.env
*.db
data/
```

### 3. The parent POM

Key parts (see `frierendb/pom.xml` for the complete file):

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <junit.version>5.11.4</junit.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.junit</groupId>
            <artifactId>junit-bom</artifactId>
            <version>${junit.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Compiler configuration (in `pluginManagement`):

```xml
<configuration>
    <release>21</release>
    <showWarnings>true</showWarnings>
    <compilerArgs>
        <arg>-Xlint:all</arg>
        <arg>-Werror</arg>
    </compilerArgs>
</configuration>
```

Enforcer rule (in `build/plugins`, bound to the `enforce` goal):

```xml
<requireJavaVersion>
    <version>[21,22)</version>
    <message>Build with JDK 21. Set JAVA_HOME to a JDK 21 installation.</message>
</requireJavaVersion>
```

### 4. The engine POM

Only a parent reference and the test dependency:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

Later (step 16) you add the `maven-jar-plugin` `test-jar` goal so the agent's tests can reuse the engine's `TestDatabases` fixture.

### 5. A test that proves the toolchain

```java
class BuildEnvironmentTest {
    @Test
    void runsOnJava21() {
        assertEquals(21, Runtime.version().feature());
    }
}
```

### 6. Run it

```bash
cd frierendb
mvn clean test
```

Then try with a different JDK on purpose and confirm the enforcer message appears.

## Pitfalls

- **`serialVersionUID` warnings**: with `-Xlint:all -Werror`, any class extending `Exception` without a `serialVersionUID` fails the build. Add `private static final long serialVersionUID = 1L;`. Do not disable the lint.
- **Build says "release version 21 not supported"**: Maven is using an older JDK; check `JAVA_HOME` and `mvn -version`.
- **Maven picks a newer JDK than 21**: the enforcer rejects it (by design). Export `JAVA_HOME` in the same shell.
- **Offline builds** (`mvn -o`) fail until dependencies and plugin providers (such as surefire's JUnit provider) have been downloaded once online.

## Tests to write

Only `BuildEnvironmentTest` (above).

## Checkpoint

`mvn clean test` prints `BUILD SUCCESS` with 1 test on JDK 21, and fails with the enforcer message on any other major version.

Commit: `build: initialize Java 21 Maven project`.
