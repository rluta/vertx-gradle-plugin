package com.darylteo.vertx.gradle.plugins

import com.darylteo.gradle.watcher.tasks.WatcherTask
import com.darylteo.vertx.gradle.configuration.ModuleConfiguration
import com.darylteo.vertx.gradle.configuration.PlatformConfiguration
import com.darylteo.vertx.gradle.configuration.ProjectConfiguration
import com.darylteo.vertx.gradle.deployments.Deployment
import com.darylteo.vertx.gradle.tasks.GenerateDeploymentConfig
import com.darylteo.vertx.gradle.tasks.GenerateModJson
import com.darylteo.vertx.gradle.tasks.RunVertx
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

public class VertxPlugin implements Plugin<Project> {
  public void apply(Project project) {
    applyPlugins project
    applyExtensions project
    addDependencies project
    addTasks project
  }

  private void applyPlugins(Project project) {
    project.with {
      apply plugin: 'java'
      apply plugin: 'watcher'
    }
  }

  private void addDependencies(Project project) {
    project.with {
      repositories { mavenCentral() }

      configurations {
        vertxcore
        vertxlang
        vertxtest
        vertxincludes

        provided {
          extendsFrom vertxcore
          extendsFrom vertxlang
          extendsFrom vertxtest
          extendsFrom vertxincludes
        }

        compile { extendsFrom provided }
      }



      afterEvaluate {
        // validations
        if (vertx?.platform?.version == null) {
          println('Vert.x Platform Version not set. e.g. "vertx.platform.version = \'2.1\'".')
        } else {
          def vertxGroup = 'io.vertx'

          dependencies {
            // core and lang modules
            vertxcore("${vertxGroup}:vertx-platform:${vertx.platform.version}")

            if (vertx.platform.lang != null) {
              def module = getModuleForLang(project, vertx.platform.lang)
              if (!module) {
                println("Unsupported Language: ${vertx.platform.lang}")
              } else {
                vertxlang(module)
              }
            }

            if (vertx.platform.toolsVersion) {
              vertxtest("${vertxGroup}:testtools:${vertx.platform.toolsVersion}")
            }

            // includes
            vertx.config?.map?.includes?.collect { String dep ->
              dep.replace('~', ':')
            }.each { dep -> vertxincludes dep }
          }
        }
      }
    }
  }

  private String getModuleForLang(Project project, String lang) {
    // load langs.properties and get the correct version if a version was not specified
    def cp = (project.configurations.vertxcore.files + project.file('conf'))
      .collect({ file ->
      // File.toURL() is bugged. Use toURI().toURL(). See Javadoc
      file.toURI().toURL()
    }).toArray(new URL[0])

    def cl = new URLClassLoader(cp)

    def props = new Properties()

    [
      'default-langs.properties',
      'langs.properties'
    ].each { file ->
      cl.getResourceAsStream(file)?.withReader { r ->
        props.load(r)
      }
    }

    // vertx modules are defined in a different format.
    def module = props.getProperty(lang)?.split(":", -1)?.getAt(0)?.replace('~', ':')
    return module
  }

  private void applyExtensions(Project project) {
    project.extensions.create 'vertx', ProjectConfiguration, project

    project.vertx.extensions.create 'platform', PlatformConfiguration, project
    project.vertx.extensions.create 'config', ModuleConfiguration, project
    project.vertx.extensions.deployments = project.container Deployment.class
  }

  private void addTasks(Project project) {
    addArchiveTasks project
    addRunTasks project
  }

  private void addArchiveTasks(Project project) {
    project.with {
      // archive tasks
      task('generateModJson', type: GenerateModJson) {}
      task('assembleVertx', type: Sync) {
      }
      task('copyMod', type: Sync) {
        into { "${rootProject.buildDir}/mods/${project.vertx.vertxName}" }
        from assembleVertx
      }
      task('modZip', type: Zip) {
        group = 'Vertx'
        classifier = 'mod'
        from assembleVertx
      }

      afterEvaluate {
        def group = vertx.info.groupId || project.group
        def name = vertx.info.artifactId || project.name
        def version = vertx.info.version || project.version

        ext.archivesBaseName = name
        assembleVertx {
          def sourceSets = sourceSets.matching({ it.name != SourceSet.TEST_SOURCE_SET_NAME })

          into "$buildDir/mod"
          from sourceSets*.output
          from generateModJson

          into('lib') {
            from configurations.compile - configurations.provided
          }

          dependsOn generateModJson
          dependsOn sourceSets*.classesTaskName
        }
      }
    }
  }

  private void addRunTasks(Project project) {
    project.with {
      // create the watcher task
      def watcherTask = task('__watch', type: WatcherTask) {
        block = false
        includes = ['src/**']
        tasks = ['copyMod']
      }

      // configure the run/debug tasks
      vertx.deployments.whenObjectAdded { Deployment dep ->
        // add tasks for deployment
        def name = dep.name.capitalize()

        def configTask = task("generate${name}Config", type: GenerateDeploymentConfig) { deployment = dep }

        def runTask = task("run$name", type: RunVertx) { debug = false }
        def debugTask = task("debug$name", type: RunVertx) { debug = true }

        [runTask, debugTask]*.configure {
          deployment dep
          configFile { configTask.outputFile }
          dependsOn configTask
        }

        afterEvaluate {
          def module = dep.deploy.module
          if (module instanceof Project) {
            runTask.dependsOn(module.copyMod)
            debugTask.dependsOn(module.copyMod)
          }

          if (!dep.platform.version) {
            dep.platform.version = vertx.platform.version
          }

          if (dep.config.autoRedeploy) {
            runTask.dependsOn(watcherTask)
            debugTask.dependsOn(watcherTask)
          }
        }
      }

      vertx.deployments.whenObjectRemoved { Deployment dep ->
        def name = dep.name.capitalize()
        tasks.removeAll tasks."run$name", tasks."debug$name"
      }

      vertx.deployments { mod { deploy project } }
    }
  }

}