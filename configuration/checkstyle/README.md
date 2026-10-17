# A static code analysis tool for JAVA

https://github.com/checkstyle/checkstyle


1) Add subtree remote
```bash
git remote add configuration ssh://git@gitlab.s7.aero:2222/ru.s7.smi/3rd-party-services/checkstyle-config.git
```

2) Pull subtree 
```bash
git subtree add --squash --prefix=configuration/checkstyle configuration <version>
```
If the required version is not known, then indicate master

3) Add to root `pom.xml`

```xml
<properties>
    <!--  actual version  -->
    <checkstyle.version>8.37</checkstyle.version>
    <maven-checkstyle-plugin.version>3.1.1</maven-checkstyle-plugin.version>
</properties>
<dependencies>
    <dependency>
        <groupId>com.puppycrawl.tools</groupId>
        <artifactId>checkstyle</artifactId>
        <version>${checkstyle.version}</version>
    </dependency>
</dependencies>

<plugins>
    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-checkstyle-plugin</artifactId>
        <version>${maven-checkstyle-plugin.version}</version>
        <dependencies>
            <dependency>
                <groupId>com.puppycrawl.tools</groupId>
                <artifactId>checkstyle</artifactId>
                <version>${checkstyle.version}</version>
            </dependency>
        </dependencies>
        <configuration>
            <configLocation>configuration/checkstyle/checkstyle.xml</configLocation>
            <sourceDirectories>
                <sourceDirectory>src/main/java</sourceDirectory>
            </sourceDirectories>
        </configuration>
        <executions>
            <execution>
                <phase>validate</phase>
                <goals>
                    <goal>check</goal>
                </goals>
            </execution>
        </executions>
    </plugin>
    <plugin>
        <groupId>com.rudikershaw.gitbuildhook</groupId>
        <artifactId>git-build-hook-maven-plugin</artifactId>
        <version>3.0.0</version>
        <configuration>
            <gitConfig>
                <core.hooksPath>configuration/checkstyle/hooks/</core.hooksPath>
            </gitConfig>
        </configuration>
        <executions>
            <execution>
                <goals>
                    <goal>configure</goal>
                </goals>
            </execution>
        </executions>
    </plugin>
</plugins>
```
Notice! If you use PluginManager, git hook and checkstyle must be out of PluginManager

4) Run from root of project `mvn clean install` for add pre-commit hook


For changes and tune rules

`./checkstyle.xml`
