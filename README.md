[![Build Status](https://travis-ci.org/Acosix/alfresco-mt-support.svg?branch=master)](https://travis-ci.org/Acosix/alfresco-mt-support)

# About

The Alfresco ECM product provides a basic multi-tenancy capability that users can utilize to logically keep different user bases and their content separate within an Alfresco installation. The support for multi-tenancy has had long standing [limitations](http://docs.alfresco.com/5.1/concepts/mt-not-implemented.html) that restrict the usefulness of the overal platform.

This module aims to provide - over time - alternative subsystem implementations that fully support the multi-tenancy capabilites of Alfresco and allow engineers / administrators to set up a system to use the same kind of functionality available to "simple" installs.

## Compatbility

This module is built to be compatible with Alfresco 5.0d and above. It may be used on either Community or Enterprise Edition. The Acosix [alfresco-utility](https://github.com/Acosix/alfresco-utility) module is a mandatory dependency for this module to work.

## Added Multi-Tenant Features

 - [Multi-Tenant LDAP/AD](https://github.com/Acosix/alfresco-mt-support/wiki/Multi-Tenant-LDAP-Authentication-and-User-Registry)
 - [Multi-Tenant Synchronization](https://github.com/Acosix/alfresco-mt-support/wiki/Multi-Tenant-Synchronization)

## Roadmap

Alfresco itself currently considers several features not to be supported in a multi-tenant environment. This module aims to support most of them eventually:

 - Inbound email (near future)
 - External authentication (near future)
 - IMAP (to be investigated)
 - Smart Folders (to be investigated)
 - Content replication (to be investigated)
 - Kerberos (to be investigated)
 - CIFS (not planned due to legacy SMB protocol support in Alfresco)
 - NTLM (not planned due to being an obsolete / insecure SSO mechanism)
 - Records Management (not planned due to extensive scope)
 - Encrypted content store (not planned - should already be supported via [simple-content-stores](https://github.com/AFaust/simple-content-stores))
 
# Maven usage

This addon is being built using the [Acosix Alfresco Maven framework](https://github.com/Acosix/alfresco-maven) and produces both AMP and installable JAR artifacts. Depending on the setup of a project that wants to include the addon, different approaches can be used to include it in the build.

## Build

This project can be build simply by executing the standard Maven build lifecycles for package, install or deploy depending on the intent for further processing. A Java Development Kit (JDK) version 8 or higher is required for the build.

For integration tests this project includes an embedded UnboundID LDAP server and H2 database. Integration tests require that the project be executed with an Arquillian test profile and container. A default profile "arquillian-tomcat-embed" has been inherited from the Acosix Maven Parent POM and is active by default.

```text
mvn clean install -P run-integration-tests
```

## Dependency in Alfresco SDK

The simplest option to include the addon in an All-in-One project is by declaring a dependency to the installable JAR artifact. Alternatively, the AMP package may be included which typically requires additional configuration in addition to the dependency.

### Using SNAPSHOT builds

In order to use a pre-built SNAPSHOT artifact published to the Open Source Sonatype Repository Hosting site, the artifact repository may need to be added to the POM, global settings.xml or an artifact repository proxy server. The following is the XML snippet for inclusion in a POM file.

```xml
<repositories>
    <repository>
        <id>ossrh</id>
        <url>https://oss.sonatype.org/content/repositories/snapshots</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

### Repository
```xml
<!-- JAR packaging -->
<dependency>
    <groupId>de.acosix.alfresco.utility</groupId>
    <artifactId>de.acosix.alfresco.utility.common</artifactId>
    <version>1.0.0.0</version>
    <type>jar</type>
</dependency>

<dependency>
    <groupId>de.acosix.alfresco.utility</groupId>
    <artifactId>de.acosix.alfresco.utility.repo</artifactId>
    <version>1.0.0.0</version>
    <type>jar</type>
    <classifier>installable</classifier>
</dependency>

<dependency>
    <groupId>de.acosix.alfresco.mtsupport</groupId>
    <artifactId>de.acosix.alfresco.mtsupport.repo</artifactId>
    <version>1.0.0.0</version>
    <type>jar</type>
    <classifier>installable</classifier>
</dependency>

<!-- OR -->

<!-- AMP packaging -->
<dependency>
    <groupId>de.acosix.alfresco.utility</groupId>
    <artifactId>de.acosix.alfresco.utility.repo</artifactId>
    <version>1.0.0.0</version>
    <type>amp</type>
</dependency>

<dependency>
    <groupId>de.acosix.alfresco.mtsupport</groupId>
    <artifactId>de.acosix.alfresco.mtsupport.repo</artifactId>
    <version>1.0.0.0</version>
    <type>amp</type>
</dependency>

<plugin>
    <artifactId>maven-war-plugin</artifactId>
    <configuration>
        <overlays>
            <overlay />
            <overlay>
                <groupId>${alfresco.groupId}</groupId>
                <artifactId>${alfresco.repo.artifactId}</artifactId>
                <type>war</type>
                <excludes />
            </overlay>
            <!-- other AMPs -->
            <overlay>
                <groupId>de.acosix.alfresco.utility</groupId>
                <artifactId>de.acosix.alfresco.utility.repo</artifactId>
                <type>amp</type>
            </overlay>
            <overlay>
                <groupId>de.acosix.alfresco.mtsupport</groupId>
                <artifactId>de.acosix.alfresco.mtsupport.repo</artifactId>
                <type>amp</type>
            </overlay>
        </overlays>
    </configuration>
</plugin>
```

For Alfresco SDK 3 beta users:

```xml
<platformModules>
    <moduleDependency>
        <groupId>de.acosix.alfresco.utility</groupId>
        <artifactId>de.acosix.alfresco.utility.repo</artifactId>
        <version>1.0.0.0</version>
        <type>amp</type>
    </moduleDependency>
    <moduleDependency>
        <groupId>de.acosix.alfresco.mtsupport</groupId>
        <artifactId>de.acosix.alfresco.mtsupport.repo</artifactId>
        <version>1.0.0.0</version>
        <type>amp</type>
    </moduleDependency>
</platformModules>
```

### Share

Currently the Share module does not provide any content / enhancements / features. 

# Other installation methods

Using Maven to build the Alfresco WAR is the **recommended** approach to install this module. As an alternative it can be installed manually.

## alfresco-mmt.jar / apply_amps

The default Alfresco installer creates folders *amps* and *amps_share* where you can place AMP files for modules which Alfresco will install when you use the apply_amps script. Place the AMPs for the *de.acosix.alfresco.utility.repo* and *de.acosix.alfresco.mtsupport.repo* modules in the *amps* directory and execute the script to install them. You must restart Alfresco for the installation to take effect.

Alternatively you can use the alfresco-mmt.jar to install the modules as [described in the documentation](http://docs.alfresco.com/5.1/concepts/dev-extensions-modules-management-tool.html).

## Manual "installation" using JAR files

Some addons and some other sources on the net suggest that you can install **any** addon by putting their JARs in a path like &lt;tomcat&gt;/lib, &lt;tomcat&gt;/shared or &lt;tomcat&gt;/shared/lib. This is **not** correct. Only the most trivial addons / extensions can be installed that way - "trivial" in this case means that these addons have no Java class-level dependencies on any component that Alfresco ships, e.g. addons that only consist of static resources, configuration files or web scripts using pure JavaScript / Freemarker.

The only way to manually install an addon using JARs that is **guaranteed** not to cause Java classpath issues is by dropping the JAR files directly into the &lt;tomcat&gt;/webapps/alfresco/WEB-INF/lib (Repository-tier) or &lt;tomcat&gt;/webapps/share/WEB-INF/lib (Share-tier) folders.

For this addon the following JARs need to be dropped into &lt;tomcat&gt;/webapps/alfresco/WEB-INF/lib:

 - de.acosix.alfresco.utility.common-&lt;version&gt;.jar
 - de.acosix.alfresco.utility.repo-&lt;version&gt;-installable.jar
 - de.acosix.alfresco.mtsupport.repo-&lt;version&gt;-installable.jar