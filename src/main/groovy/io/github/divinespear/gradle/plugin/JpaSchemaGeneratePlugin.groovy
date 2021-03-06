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

import io.github.divinespear.gradle.plugin.config.Configuration

import org.eclipse.persistence.config.PersistenceUnitProperties
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin

class JpaSchemaGeneratePlugin implements Plugin<Project> {

    void apply(Project project) {
        project.plugins.apply(JavaPlugin)
        // tasks
        def task = project.task("generateSchema", type: JpaSchemaGenerateTask)
        task.dependsOn(project.tasks.classes)
        // plugin extensions
        project.extensions.create("generateSchema", Configuration)
        project.generateSchema {
            skip = false

            persistenceXml = PersistenceUnitProperties.ECLIPSELINK_PERSISTENCE_XML_DEFAULT
            persistenceUnitName = "default"

            databaseAction = PersistenceUnitProperties.SCHEMA_GENERATION_NONE_ACTION
            scriptAction = PersistenceUnitProperties.SCHEMA_GENERATION_NONE_ACTION

            outputDirectory = new File(project.buildDir, "generated-schema")
            createOutputFileName = "create.sql"
            dropOutputFileName = "drop.sql"

            createSourceMode = PersistenceUnitProperties.SCHEMA_GENERATION_METADATA_SOURCE
            dropSourceMode = PersistenceUnitProperties.SCHEMA_GENERATION_METADATA_SOURCE

            lineSeparator = "LF"
        }
        project.generateSchema.extensions.targets = project.container(Configuration)
    }
}
