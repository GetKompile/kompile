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
  &emsp;&emsp;public void testReshapeOperations() {  \
  &emsp;&emsp;&emsp;&emsp;INDArray nd = Nd4j.create(new float[]{1, 2, 3, 4}, 2, 2); \
  &emsp;&emsp;&emsp;&emsp;INDArray ndv; \
  &emsp;&emsp;&emsp;&emsp;ndv = nd.transpose() \
  &emsp;&emsp;}  \
  &emsp;&emsp;@Test  \
  &emsp;&emsp;public void testAccumulateOperations() {  \
  &emsp;&emsp;&emsp;&emsp;INDArray originalArray = Nd4j.linspace(1,15,15).reshape('c',3,5);   \
  &emsp;&emsp;&emsp;&emsp;INDArray avgAlong1 = originalArray.mean(1);   \
  &emsp;&emsp;&emsp;&emsp;INDArray argMaxAlongDim0 = Nd4j.argMax(originalArray,0);   \
  &emsp;&emsp;}  \
  }
- Finally, we can run the command to generate the configuration files: \
  mvn clean install
  
### Second option: Run sub-process runners inside a parent process
- Tracing Agent generates metadata by checking which classes are used for specific purposes. Therefore, other than unit test suite,
  we can use a main process which contains many sub-processes with an embedded agent for generating metadata.
- Sub-processes are wrapped inside Runners, which are the interface implementations that run basic flows of our application. \
  Example of ConfigGeneratorRunner interface: \
   public interface ConfigGeneratorRunner { \
   &emsp;&emsp;void generateConfigFiles(); \
   }
- Next, we will write our runner classes. \
  The runner classes implement the BasedRunner interface, which has a generateConfigFiles() method. We provide all running flows in its run method().
  Here's example of a runner class, we run the Add and Multiply methods of Nd4j : \
  public class Nd4jRunner implements ConfigGeneratorRunner { \
  &emsp;&emsp;public static void main(String[] args) { \
  &emsp;&emsp;&emsp;&emsp;new Nd4jRunner().generateConfigFiles(); \
  &emsp;&emsp;} \
  &emsp;&emsp;@Override \
  &emsp;&emsp;public void generateConfigFiles() { \
  &emsp;&emsp;&emsp;&emsp;testBasedOperations(); \
  &emsp;&emsp;&emsp;&emsp;testReshapeOperations(); \
  &emsp;&emsp;&emsp;&emsp;testReshapeOperations(); \
  &emsp;&emsp;} \
  &emsp;&emsp;private void testElementWiseOperations() { \
  &emsp;&emsp;&emsp;&emsp;INDArray nd1 = Nd4j.create(new double[] {1, 2, 3, 4, 5, 6}, 2, 3);  \
  &emsp;&emsp;&emsp;&emsp;INDArray nd2 = Nd4j.rand(3, 5); \
  &emsp;&emsp;&emsp;&emsp;INDArray nd3 = Nd4j.zeros(1, 440); \
  &emsp;&emsp;&emsp;&emsp;INDArray nd4 = Nd4j.ones(1, 440); \
  &emsp;&emsp;&emsp;&emsp;INDArray nd5 = Nd4j.accumulate(nd3, nd4); \
  &emsp;&emsp;&emsp;&emsp;INDArray nd6 = Nd4j.concat(0, nd4, nd5); \
  &emsp;&emsp;} \
  &emsp;&emsp;private void testReshapeOperations(INDArray nd) { \
  &emsp;&emsp;&emsp;&emsp;INDArray nd = Nd4j.create(new float[]{1, 2, 3, 4}, 2, 2); \
  &emsp;&emsp;&emsp;&emsp;INDArray ndv; \
  &emsp;&emsp;&emsp;&emsp;ndv = nd.transpose() \
  &emsp;&emsp;} \
  &emsp;&emsp;private void testElementWiseOperations() { \
  &emsp;&emsp;&emsp;&emsp;INDArray nd1 = Nd4j.create(new double[]{1,2,3,4,5,6}, 2,3); \
  &emsp;&emsp;&emsp;&emsp;INDArray nd2 = Nd4j.create(new double[]{10,20}, 2,1); //vector as column \
  &emsp;&emsp;&emsp;&emsp;INDArray nd3 = Nd4j.create(new double[]{30,40,50}, 1, 3); //vector as row \
  &emsp;&emsp;&emsp;&emsp;INDArray ndv1 = nd1.addColumnVector(nd2); \
  &emsp;&emsp;&emsp;&emsp;INDArray ndv2 = nd1.addRowVector(nd3); \
  &emsp;&emsp;} \
  }
- We can use the RunnerExecutor to execute the Runner: \
  public class RunnerExecutor { \
  &emsp;&emsp;public void execute(ConfigGeneratorRunner basedRunner) { \
  &emsp;&emsp;&emsp;&emsp;ProcessExecutor processExecutor = new ProcessExecutor(getJavaPath(), TRACING_AGENT_OPTION, "-cp", getClasspath(), basedRunner.getClass().getName()); \
  &emsp;&emsp;&emsp;&emsp;processExecutor.execute(); \
  &emsp;&emsp;} \
  &emsp;&emsp;private String getJavaPath() { \
  &emsp;&emsp;&emsp;&emsp;String separator = System.getProperty("file.separator"); \
  &emsp;&emsp;&emsp;&emsp;return System.getProperty("java.home") + separator + "bin" + separator + "java"; \
  &emsp;&emsp;} \
  &emsp;&emsp;private String getClasspath() { \
  &emsp;&emsp;&emsp;&emsp;return System.getProperty("java.class.path"); \
  &emsp;&emsp;} \
  } \
  We use zt-exec to execute the command line with a tracing-agent embedded. \
  We should pay attention to classpath of the sub-process. The sub process will always use the classpath of the parent process.
  We can get the classpath of current process by this way: \
  &emsp;&emsp;System.getProperty("java.class.path");
- Finally, we write a main process that load and run all sub-processes with tracing agents: \
  We will use ClassLoader or Reflection to find all the Runners in the predefined package. \
  public class MainApplication {\
  &emsp;&emsp;public static void main(String[] args) {\
  &emsp;&emsp;&emsp;&emsp;List<Class> runnerClasses = findAllClassesWithinPackage("dev.danvega");\
  &emsp;&emsp;&emsp;&emsp;runnerClasses.forEach(klass -> {\
  &emsp;&emsp;&emsp;&emsp;&emsp;&emsp;new RunnerExecutor().execute(klass.getConstructor(String.class).newInstance());\
  &emsp;&emsp;&emsp;&emsp;});\
  &emsp;&emsp;}\
  }
   
Poc Reference link: https://github.com/ndthanhit/POCs

## Consequences
### Advantages
- For the UT Suite approach, we can utilize existing unit test suites, this will save time for us.
- For the sub-process approach, we can choose to run main-flow sub-processes. In this way, we have more control over the code.

### Disadvantages
- For the UT Suite approach, even if we can utilize the existing unit test suites, we still have to collect and reorganize all the test suites into one place.
- For the sub-process approach, we almost have to start from scratch, and then it takes more time for us to write sub-process classes.
- Both approaches have a code coverage problem. We can't make sure that our test suites or sub-processes classes have 100% coverage. This will affect the quality of the metadata.