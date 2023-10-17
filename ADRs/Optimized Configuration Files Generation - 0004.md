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
### First option: Running tracing agent with a Unit test Suite:
1. Step 1: Writing an overall unit test suite which includes all the library that we use: \
   Example: Test class for Nd4j: \
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
2. Step 2: Configure the tracing agent in pom.xml file:  \
   \<plugin>  \
   &emsp;&emsp;\<groupId>org.apache.maven.plugins\</groupId> \
   &emsp;&emsp;\<artifactId>maven-surefire-plugin\</artifactId> \
   &emsp;&emsp;\<version>2.12.4\</version> \
   &emsp;&emsp;\<configuration> \
   &emsp;&emsp;&emsp;&emsp;\<argLine>-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image\</argLine> \
   &emsp;&emsp;\</configuration> \
   &emsp;&emsp;\</plugin>
3. Step 3: Run the command line to generate configuration files:  \
    mvn clean install  \
   Poc Reference link: https://github.com/ndthanhit/POCs
  
### Second option: Run sub-process runners inside a parent process
1. Step 1: Write a based Runner interface: \
   public interface BasedRunner { \
   &emsp;&emsp;void run(); \
   }
2. Step 2: Write runner classes: \
   Example: \
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
3. Write a main process that load and run all sub-processes with tracing agents:
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
- The process of generating configuration files is automatic. Each time we have changes in Konduit/Dl4j, we don't have to manually edit the .json configuration files.
- We just include only the classes and methods that are used to run the CLI, so the native image size will be decreased.

### Disadvantages
- Required to write all the cases of the test suite. It's somehow impossible to have code coverage 100%.