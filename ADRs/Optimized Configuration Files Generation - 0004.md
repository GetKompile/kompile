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
- Graalvm provides a Tracing Agent to easily gather metadata and prepare configuration files.
- The agent tracks all usages of dynamic features during application execution on a regular Java VM.
- We can enable the agent by the command line as below:
  $JAVA_HOME/bin/java -agentlib:native-image-agent=config-output-dir=/path/to/config-dir/ ...
- When running, the agent looks up classes, methods, fields, resources for which the native-image tool needs. When
  the application completes, and the JVM exits, the agent writes metadata to Json files in the specified output
  directory.
- Therefore, we can utilize this tool for our generating metadata step.

### First option: Running tracing agent with a Unit test Suite:
- Graalvm also supports generating metadata while you run a UT suite, we just need to add the plugin into pom.xml file:\
  &emsp;&emsp;\<plugin>  \
  &emsp;&emsp;&emsp;&emsp;\<groupId>org.apache.maven.plugins\</groupId> \
  &emsp;&emsp;&emsp;&emsp;\<artifactId>maven-surefire-plugin\</artifactId> \
  &emsp;&emsp;&emsp;&emsp;\<version>2.12.4\</version> \
  &emsp;&emsp;&emsp;&emsp;\<configuration> \
  &emsp;&emsp;&emsp;&emsp;&emsp;&emsp;\<argLine>-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image\</argLine> \
  &emsp;&emsp;&emsp;&emsp;\</configuration> \
  &emsp;&emsp;\</plugin>

- Then we can write our own unit test suite which run basic flows of our libraries:
  Below is the example of Unit test class for Nd4j, this class will contain all test methods of Nd4j class. \
  public class Nd4jTest {  \
  &emsp;&emsp;@Test  \
  &emsp;&emsp;public void testNd4jAdd() {  \
  &emsp;&emsp;&emsp;&emsp;// ...  \
  &emsp;&emsp;}  \
  &emsp;&emsp;@Test  \
  &emsp;&emsp;public void testNd4jMultiply() {  \
  &emsp;&emsp;&emsp;&emsp;// ...  \
  &emsp;&emsp;}  \
  }
- Finally, we can run the command to generate the configuration files: \
  mvn clean install
  
### Second option: Run sub-process runners inside a parent process
- Tracing Agent generates metadata by checking which classes are used for specific purposes. Therefore, other than unit test suite,
  we can use a main process which contains many sub-processes with an embedded agent for generating metadata.
- Sub-processes are wrapped inside Runners, which are the interface implementations that run basic flows of our application.
  Example of BasedRunner interface: \
   public interface BasedRunner { \
   &emsp;&emsp;void run(); \
   }
- Next, we will write our runner classes. \
  The runner classes implement the BasedRunner interface, which has a run() method. We provide all running flows in its run method().
  Here's example of a runner class, we run the Add and Multiply methods of Nd4j : \
   public class Nd4jRunner implements BasedRunner { \
   &emsp;&emsp;public static void main(String[] args) { \
   &emsp;&emsp;&emsp;&emsp;new Nd4jRunner().run(); \
   &emsp;&emsp;} \
   &emsp;&emsp;@Override \
   &emsp;&emsp;public void run() { \
   &emsp;&emsp;&emsp;&emsp;runAddMethod(); \
   &emsp;&emsp;&emsp;&emsp;runMultiplyMethod(); \
   &emsp;&emsp;} \
   &emsp;&emsp;private void runAddMethod() { \
   &emsp;&emsp;&emsp;&emsp; // ... \
   &emsp;&emsp;} \
   &emsp;&emsp;private void runMultiplyMethod() { \
   &emsp;&emsp;&emsp;&emsp; // ... \
   &emsp;&emsp;} \
   }
3. Finally, we write a main process that load and run all sub-processes with tracing agents: \
   We will use ClassLoader or Reflection to find all the Runners in the predefined package. 
   Then we use zt-exec to execute the command line with a tracing-agent embedded. \
   public class MainApplication { \
   &emsp;&emsp;public static void main(String[] args) { \
   &emsp;&emsp;&emsp;&emsp;List<Class> runnerClasses = findAllClassesWithinPackage("dev.danvega"); \
   &emsp;&emsp;&emsp;&emsp;runnerClasses.forEach(klass -> { \
   &emsp;&emsp;&emsp;&emsp;&emsp;&emsp;ProcessExecutor processExecutor = new ProcessExecutor(path, "-cp", classpath, klass.getName()); \
   &emsp;&emsp;&emsp;&emsp;}); \
   &emsp;&emsp;} \
   } \
   
Poc Reference link: https://github.com/ndthanhit/POCs

## Consequences
### Advantages
- For the UT Suite approach, we can utilize existing unit test suites, this will save time for us.
- For the sub-process approach, we can choose to run main-flow sub-processes. In this way, we have more control over the code.

### Disadvantages
- For the UT Suite approach, even if we can utilize the existing unit test suites, we still have to collect and reorganize all the test suites into one place.
- For the sub-process approach, we almost have to start from scratch, and then it takes more time for us to write sub-process classes.
- Both approaches have a code coverage problem. We can't make sure that our test suites or sub-processes classes have 100% coverage. This will affect the quality of the metadata.