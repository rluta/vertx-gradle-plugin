package org.vertx.gradle

import org.gradle.api.*

import groovy.json.*

import java.nio.file.Files

class VertxModulePlugin implements Plugin<Project> {
  void apply(Project project) {
    project.ext.vertx = true

    def props = new String(Files.readAllBytes(project.file('src/main/resources/mod.json').toPath()))
    def modjson = new JsonSlurper().parseText(props)

    println modjson.main
  }
}