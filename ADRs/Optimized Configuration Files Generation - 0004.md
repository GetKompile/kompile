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
1. Step 1: Writing an overall unit test suite: \
   @Test \
   public void testNd4j() { \
   &emsp;&emsp;INDArray nd1 = Nd4j.create(new double[] {1, 2, 3, 4, 5, 6}, 2, 3); \
   &emsp;&emsp;INDArray ndv = nd1.add(1); \
   &emsp;&emsp;ndv = nd1.mul(5); \
   &emsp;&emsp;ndv = nd1.sub(3); \
   &emsp;&emsp;ndv = nd1.div(2); \
   &emsp;&emsp;// asserts() \
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
1. Step 1: Write sub processes:  \
   public class Nd4jRunner { \
   &emsp;&emsp;public static void main(String[] args) { \
   &emsp;&emsp;&emsp;&emsp;INDArray nd1 = Nd4j.create(new double[] {1, 2, 3, 4, 5, 6}, 2, 3); \
   &emsp;&emsp;&emsp;&emsp;INDArray ndv = nd1.add(1); \
   &emsp;&emsp;&emsp;&emsp;ndv = nd1.mul(5); \
   &emsp;&emsp;&emsp;&emsp;ndv = nd1.sub(3); \
   &emsp;&emsp;&emsp;&emsp;ndv = nd1.div(2); \
   &emsp;&emsp;} \
   } 
2. Step 2: Write a main process that load and run all sub processes with tracing agent: \
   public class MainApplication { \
   &emsp;&emsp;public static void main(String[] args) throws IOException, InterruptedException, TimeoutException { \
   &emsp;&emsp;&emsp;&emsp;String separator = System.getProperty("file.separator"); \
   &emsp;&emsp;&emsp;&emsp;String path = System.getProperty("java.home") + separator + "bin" + separator + "java"; \
   &emsp;&emsp;&emsp;&emsp;String classpath = System.getProperty("java.class.path"); \
   &emsp;&emsp;&emsp;&emsp;ProcessExecutor processExecutor = new ProcessExecutor(path, "-agentlib:native-image-agent=config-output-dir=META-INF/native-image/", "-cp", classpath, Nd4jRunner.class.getName()); \
   &emsp;&emsp;&emsp;&emsp;String output = processExecutor.readOutput(true).execute().outputUTF8(); \
   &emsp;&emsp;&emsp;&emsp;System.out.println(output); \
   &emsp;&emsp;} \
   } \
   Poc Reference link: https://github.com/ndthanhit/POCs
   
## Consequences
### Advantages
- The process of generating configuration files is automatic. Each time we have changes in Konduit/Dl4j, we don't have to manually edit the .json configuration files.
- We just include only the classes and methods that are used to run the CLI, so the native image size will be decreased.

### Disadvantages
- Required to write all the cases of the test suite. It's somehow impossible to have code coverage 100%.