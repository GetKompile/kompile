# Build a tool to automatically generate more optimized configuration files.

## Status
Proposed

Proposed by: Nguyen Dang Thanh (05-10-2023)

Discussed with: Adam Gibson

## Context
- The Graalvm native-image build tool uses some configuration files to solve dynamic aspects of the application such as reflection, jni, and resources...
- Currently, all the classes that are needed to put on configuration files are 102 classes in Kompile, and many libraries (google, javacpp, jdk, org.nd4j, org.bytedeco, konduit).
- Right now, the configuration files are manually built, and they contain many redundant classes that are not needed in the build process.
- Therefore, we plan to have a "tool" that generates these configuration files with two objectives: automatic and efficient.

## Proposal
- Graalvm provides a Tracing Agent to easily gather metadata  and prepare configuration files.
- The agent tracks all usages of dynamic features during application execution on a regular Java VM.
- We can enable the agent con the command line as below:
  $JAVA_HOME/bin/java -agentlib:native-image-agent=config-output-dir=/path/to/config-dir/ ...
- When run, the agent looks up classes, methods, fields, resources for which the native-image tools needs. When
  the application completes, and the JVM exits, the agent writes metadata to Json files in the specified output
  directory.
- Therefore, we can utilize this tool for our generating metadata step.

### First option: Running tracing agent with a Unit test Suite:
- Graalvm also supports generating metadata while you run a Unit Test Suite, we just need to add the plugin into pom.xml file: \
  &emsp;&emsp;\<plugin>  \
  &emsp;&emsp;&emsp;&emsp;\<groupId>org.apache.maven.plugins\</groupId> \
  &emsp;&emsp;&emsp;&emsp;\<artifactId>maven-surefire-plugin\</artifactId> \
  &emsp;&emsp;&emsp;&emsp;\<version>2.12.4\</version> \
  &emsp;&emsp;&emsp;&emsp;\<configuration> \
  &emsp;&emsp;&emsp;&emsp;&emsp;&emsp;\<argLine>-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image\</argLine> \
  &emsp;&emsp;&emsp;&emsp;\</configuration> \
  &emsp;&emsp;\</plugin>
- Then we can write our own test suite and run mvn command for generating metadata.
  
### Second option: Run sub-process runners inside a parent process
- Tracing Agent generates metadata by checking which classes are used for specific purposes. Therefore, other than unit test suite, 
  we can use a main process which contains many sub-processes with an embedded agent for generating metadata.
- From the main process, we can use zt-exec to exec the sub-processes: \
  new ProcessExecutor(path, "-agentlib:native-image-agent=config-output-dir=META-INF/native-image", "-cp", classpath, SubProcess.getName())
- Note: We should pay attention to classpath of the sub-process. The sub process will always use the classpath of the parent process.
  We can get the classpath of current process by this way: \
  &emsp;&emsp;System.getProperty("java.class.path");

Poc Reference link: https://github.com/ndthanhit/POCs

## Consequences
### Advantages
- The process of generating configuration files is automatic. Each time we have changes in Konduit/Dl4j, we don't have to manually edit the .json configuration files.
- We just include only the classes and methods that are used to run the CLI, so the native image size will be decreased.

### Disadvantages
- Required to write all the cases of the test suite. It's somehow impossible to have code coverage 100%.