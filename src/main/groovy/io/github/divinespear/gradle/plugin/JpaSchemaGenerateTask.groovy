/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.github.divinespear.gradle.plugin

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Driver
import java.sql.DriverManager

import javax.persistence.Persistence
import javax.persistence.spi.PersistenceProvider

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo
import org.springframework.orm.jpa.persistenceunit.DefaultPersistenceUnitManager

import io.github.divinespear.gradle.plugin.config.Configuration

class JpaSchemaGenerateTask extends DefaultTask {

    List<Configuration> getTargets() {
        def List<Configuration> list = []

        project.generateSchema.targets.all { target ->
            list.add(new Configuration(target.name, project.generateSchema, target))
        }
        if (list.empty) {
            list.add(project.generateSchema)
        }

        return list
    }

    ClassLoader getProjectClassLoader(boolean scanTestClasses) {
        def classfiles = [] as Set
        // compiled classpath
        classfiles += [
            project.sourceSets.main.output.classesDir,
            project.sourceSets.main.output.resourcesDir
        ]
        // include test classpath
        if (scanTestClasses) {
            classfiles += [
                project.sourceSets.test.output.classesDir,
                project.sourceSets.test.output.resourcesDir
            ]
        }
        // convert to url
        def classURLs = []
        classfiles.each {
            classURLs << it.toURI().toURL()
        }

        // dependency artifacts to url
        project.configurations.runtime.each {
            classURLs << it.toURI().toURL()
        }

        // logs
        classURLs.each {
            logger.info("  * classpath: " + it)
        }

        return new URLClassLoader(classURLs.toArray(new URL[0]), this.class.classLoader)
    }

    String detectVendor(Configuration config) {
        def vendorName = config.vendor
        // check vendor name
        if (PERSISTENCE_PROVIDER_MAP[vendorName.toLowerCase()] != null) {
            logger.info("using ${vendorName}")
            return vendorName.toLowerCase()
        }
        // check vendor class name
        PERSISTENCE_PROVIDER_MAP.each {
            if (it.value == vendorName) {
                vendorName = it.key
                logger.info("found ${vendorName} from ${config.vendor}")
            }
        }
        if (vendorName != null) {
            return vendorName
        }
        // try persistence.xml
        def xml = new File(config.persistenceXml).text
        def matcher = xml =~ /<provider>([^<]+)<\/provider>/
        PERSISTENCE_PROVIDER_MAP.each {
            if (it.value == matcher[0][1]) {
                vendorName = it.key
                logger.info("found ${vendorName} from persistence.xml")
            }
        }
        // no more match
        return vendorName
    }


    Map<String, Object> persistenceProperties(Configuration target) {
        Map<String, Object> map = [:]

        // mode
        map[Configuration.JAVAX_SCHEMA_GENERATION_DATABASE_ACTION] = target.databaseAction.toLowerCase()
        map[Configuration.JAVAX_SCHEMA_GENERATION_SCRIPTS_ACTION] = target.scriptAction.toLowerCase()
        // output files
        if (target.scriptTarget) {
            if (target.outputDirectory == null) {
                throw new IllegalArgumentException("outputDirectory is REQUIRED for script generation.")
            }
            def outc = new File(target.outputDirectory, target.createOutputFileName).toURI().toString()
            def outd = new File(target.outputDirectory, target.dropOutputFileName).toURI().toString()
            map[Configuration.JAVAX_SCHEMA_GENERATION_SCRIPTS_CREATE_TARGET] = outc
            map[Configuration.JAVAX_SCHEMA_GENERATION_SCRIPTS_DROP_TARGET] = outd
        }
        // database emulation options
        map[Configuration.JAVAX_SCHEMA_DATABASE_PRODUCT_NAME] = target.databaseProductName
        map[Configuration.JAVAX_SCHEMA_DATABASE_MAJOR_VERSION] = target.databaseMajorVersion?.toString()
        map[Configuration.JAVAX_SCHEMA_DATABASE_MINOR_VERSION] = target.databaseMinorVersion?.toString()
        // database options
        if (target.databaseTarget) {
            if (target.jdbcUrl == null) {
                throw new IllegalArgumentException("jdbcUrl is REQUIRED for database generation.")
            }
        }
        map[Configuration.JAVAX_JDBC_DRIVER] = target.jdbcDriver
        map[Configuration.JAVAX_JDBC_URL] = target.jdbcUrl
        map[Configuration.JAVAX_JDBC_USER] = target.jdbcUser
        map[Configuration.JAVAX_JDBC_PASSWORD] = target.jdbcPassword
        // source selection
        map[Configuration.JAVAX_SCHEMA_GENERATION_CREATE_SOURCE] = target.createSourceMode
        if (target.createSourceFile == null) {
            if (!Configuration.JAVAX_SCHEMA_GENERATION_METADATA_SOURCE.equals(target.createSourceMode)) {
                throw new IllegalArgumentException("create source file is required for mode " + target.createSourceMode)
            }
        } else {
            map[Configuration.JAVAX_SCHEMA_GENERATION_CREATE_SCRIPT_SOURCE] = target.createSourceFile.toURI().toString()
        }
        map[Configuration.JAVAX_SCHEMA_GENERATION_DROP_SOURCE] = target.dropSourceMode
        if (target.dropSourceFile == null) {
            if (!Configuration.JAVAX_SCHEMA_GENERATION_METADATA_SOURCE.equals(target.dropSourceMode)) {
                throw new IllegalArgumentException("drop source file is required for mode " + target.dropSourceMode)
            }
        } else {
            map[Configuration.JAVAX_SCHEMA_GENERATION_DROP_SCRIPT_SOURCE] = target.dropSourceFile.toURI().toString()
        }

        /*
         * EclipseLink specific
         */
        // persistence.xml
        map[Configuration.ECLIPSELINK_PERSISTENCE_XML] = target.persistenceXml
        // weaving
        map[Configuration.ECLIPSELINK_WEAVING] = "false"

        /*
         * Hibernate specific
         */
        map[Configuration.HIBERNATE_AUTODETECTION] = "class,hbm"
        // dialect (without jdbc connection)
        if ((target.jdbcUrl ?: "").empty && (map[Configuration.HIBERNATE_DIALECT] ?: "").empty) {
            DialectResolutionInfo info = new DialectResolutionInfo() {
                        String getDriverName() { null }
                        int getDriverMajorVersion() { 0}
                        int getDriverMinorVersion() { 0 }
                        String getDatabaseName() { target.databaseProductName }
                        int getDatabaseMajorVersion() { target.databaseMajorVersion ?: 0 }
                        int getDatabaseMinorVersion() { target.databaseMinorVersion ?: 0 }
                    }
            def detectedDialect = StandardDialectResolver.INSTANCE.resolveDialect(info)
            map[Configuration.HIBERNATE_DIALECT] = detectedDialect.getClass().getName()
        }

        /*
         * DataNucleus specific
         */
        // persistence.xml
        map[Configuration.DATANUCLEUS_PERSISTENCE_XML] = target.persistenceXml

        /*
         * Override properties
         */
        map.putAll(target.properties)

        // issue-3: pass mock connection
        if (!target.databaseTarget && (target.jdbcUrl ?: "").empty) {
            map[Configuration.JAVAX_SCHEMA_GEN_CONNECTION] =  new ConnectionMock(target.databaseProductName, target.databaseMajorVersion, target.databaseMinorVersion)
        }
        // issue-5: pass "none" for avoid validation while schema generating
        map[Configuration.JAVAX_VALIDATION_MODE] = "none"
        
        // issue-24: remove null value before reset JTA
        map.findAll { it.value != null }

        // issue-13: disable JTA and datasources
        map[Configuration.JAVAX_TRANSACTION_TYPE] = "RESOURCE_LOCAL"
        map[Configuration.JAVAX_JTA_DATASOURCE] = null
        map[Configuration.JAVAX_NON_JTA_DATASOURCE] = null

        logger.info('--- configuration begin ---')
        logger.info(map.toString())
        logger.info('--- configuration end ---')
        
        map
    }

    private Map<String, String> LINE_SEPARAOR_MAP = ["CRLF": "\r\n", "LF": "\n", "CR": "\r"]

    void postProcess(Configuration target) {
        if (target.outputDirectory == null) {
            return
        }

        final def linesep = LINE_SEPARAOR_MAP[target.lineSeparator?.toUpperCase()]?: (System.properties["line.separator"]?:"\n")

        def files = [
            new File(target.outputDirectory, target.createOutputFileName),
            new File(target.outputDirectory, target.dropOutputFileName)
        ]
        files.each { file ->
            if (file.exists()) {
                def tmp = File.createTempFile("script-", null, target.outputDirectory)
                try {
                    file.withReader { reader ->
                        def line = null
                        while ((line = reader.readLine()) != null) {
                            line.replaceAll(/(?i)((?:create|drop|alter)\s+(?:table|view|sequence))/, ";\$1").split(";").each {
                                def s = it?.trim() ?: ""
                                if (!s.empty) {
                                    tmp << (target.format ? format(s, linesep) : s) + ";" + linesep + (target.format ? linesep : "")
                                }
                            }
                        }
                    }
                } finally {
                    Files.copy(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    tmp.delete()
                }
            }
        }
    }

    String format(String s, String linesep) {
        s = s.replaceAll(/^([^(]+\()/, "\$1\r\n\t").replaceAll(/\)[^()]*$/, "\r\n\$0").replaceAll(/((?:[^(),\s]+|\S\([^)]+\)[^),]*),)\s*/, "\$1\r\n\t")
        def result = ""
        def completed = true
        if (s =~ /(?i)^create(\s+\S+)?\s+(?:table|view)/) {
            // create table/view
            s.split("\r\n").each {
                if (it =~ /^\S/) {
                    if (!completed) {
                        result += linesep
                    }
                    result += (it + linesep)
                } else if (completed) {
                    if (it =~ /^\s*[^(]+(?:[^(),\s]+|\S\([^)]+\)[^),]*),\s*$/) {
                        result += (it + linesep)
                    } else {
                        result += it
                        completed = false
                    }
                } else {
                    result += it.trim()
                    if (it =~ /[^)]+\).*$/) {
                        result += linesep
                        completed = true
                    }
                }
            }
        } else if (s =~  /(?i)^create(\s+\S+)?\s+index/) {
            // create index
            s.replaceAll(/(?i)^(create(\s+\S+)?\s+index\s+\S+)\s*/, '\$1\r\n\t').split("\r\n").each {
                if (result.isEmpty()) {
                    result += (it + linesep)
                } else if (completed) {
                    if (it =~ /^\s*[^(]+(?:[^(),\s]+|\S\([^)]+\)[^),]*),\s*$/) {
                        result += (it + linesep)
                    } else {
                        result += it
                        completed = false
                    }
                } else {
                    result += it.trim()
                    if (it =~ /[^)]+\).*$/) {
                        result += linesep
                        completed = true
                    }
                }
            }
            result = result.replaceAll(/(?i)(asc|desc)\s*(on)/, '\$2')
        } else if (s =~  /(?i)^alter\s+table/) {
            // alter table
            s.replaceAll(/(?i)^(alter\s+table\s+\S+)\s*/, '\$1\r\n\t').replaceAll(/(?i)\)\s*(references)/, ')\r\n\t\$1').split("\r\n").each {
                if (result.isEmpty()) {
                    result += (it + linesep)
                } else if (completed) {
                    if (it =~ /^\s*[^(]+(?:[^(),\s]+|\S\([^)]+\)[^),]*),\s*$/) {
                        result += (it + linesep)
                    } else {
                        result += it
                        completed = false
                    }
                } else {
                    result += it.trim()
                    if (it =~ /[^)]+\).*$/) {
                        result += linesep
                        completed = true
                    }
                }
            }
        } else {
            result = (s.trim() + linesep)
        }
        result.trim()
    }

    @TaskAction
    void generate() {
        this.getTargets().each { target ->
            // update vendor name
            if (target.vendor != null) {
                target.vendor = detectVendor(target)
            }
            // create output directory
            if (target.outputDirectory != null) {
                target.outputDirectory.mkdirs()
            }
            // get classloader
            def classloader = this.getProjectClassLoader(target.scanTestClasses)
            // load JDBC driver if necessary
            if (target.jdbcDriver?.length() > 0) {
                def driver = classloader.loadClass(target.jdbcDriver).newInstance() as Driver
                DriverManager.registerDriver(driver)
            }
            // generate
            def thread = Thread.currentThread()
            def contextClassLoader = thread.getContextClassLoader() as ClassLoader
            try {
                thread.setContextClassLoader(classloader)
                if (target.vendor == null) {
                    logger.info("* generate using persistence.xml")
                    defaultGenerate(target)
                } else {
                    logger.info("* generate without persistence.xml")
                    xmllessGenerate(target)
                }
            } finally {
                thread.setContextClassLoader(contextClassLoader)
            }
            // post-process
            this.postProcess(target)
        }
    }

    void defaultGenerate(Configuration config) {
        def props = persistenceProperties(config)
        Persistence.generateSchema(config.persistenceUnitName, props)
    }

    private static final Map<String, String> PERSISTENCE_PROVIDER_MAP = [
        'eclipselink': 'org.eclipse.persistence.jpa.PersistenceProvider',
        'hibernate': 'org.hibernate.jpa.HibernatePersistenceProvider',
        'datanucleus': 'org.datanucleus.api.jpa.PersistenceProviderImpl'
    ]

    void xmllessGenerate(Configuration config) {
        def vendorName = config.vendor
        def providerClassName = PERSISTENCE_PROVIDER_MAP[vendorName.toLowerCase()]
        if (providerClassName == null && PERSISTENCE_PROVIDER_MAP.values().contains(vendorName)) {
            providerClassName = vendorName
        }
        if ((providerClassName ?: '').empty) {
            throw new IllegalArgumentException("vendor name or provider class name is required on xml-less mode.")
        }
        def provider = Class.forName(providerClassName).newInstance() as javax.persistence.spi.PersistenceProvider

        if (config.packageToScan.empty) {
            throw new IllegalArgumentException("packageToScan is required on xml-less mode.")
        }

        def props = persistenceProperties(config)
        def manager = new DefaultPersistenceUnitManager()
        manager.defaultPersistenceUnitName = config.persistenceUnitName
        manager.packagesToScan = (manager.packagesToScan ?: []) + config.packageToScan as String[]
        manager.afterPropertiesSet()

        def info = manager.obtainDefaultPersistenceUnitInfo()
        info.persistenceProviderClassName = provider.class.name
        info.properties.putAll(props.findAll { it.value != null })

        if (config.vendor == "datanucleus") {
            // datanucleus must need persistence.xml
            final def persistencexml = File.createTempFile("persistence-", ".xml", project.sourceSets.main.output.classesDir)
            persistencexml.deleteOnExit()
            persistencexml << """<?xml version="1.0" encoding="utf-8" ?>
<persistence version="2.1"
    xmlns="http://xmlns.jcp.org/xml/ns/persistence" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/persistence http://www.oracle.com/webfolder/technetwork/jsc/xml/ns/persistence/persistence_2_1.xsd">
    <persistence-unit name="${info.persistenceUnitName}" transaction-type="RESOURCE_LOCAL">
        <provider>org.datanucleus.api.jpa.PersistenceProviderImpl</provider>
        <exclude-unlisted-classes>false</exclude-unlisted-classes>
    </persistence-unit>
</persistence>"""
            props[Configuration.DATANUCLEUS_PERSISTENCE_XML] = persistencexml.absoluteFile.toURI().toString()

            // datanucleus does not support execution order...
            props.remove(Configuration.JAVAX_SCHEMA_GENERATION_CREATE_SOURCE)
            props.remove(Configuration.JAVAX_SCHEMA_GENERATION_DROP_SOURCE)
        }

        provider.generateSchema(info, props)
    }

}
