# Build a tool to automatically generate more optimized configuration files.

## Status
Proposed

Proposed by: Nguyen Dang Thanh (05-10-2023)

Discussed with: Adam Gibson

## Context
- Graalvm native-image build tool uses some configuration files to solve dynamic aspects of the application such as: reflection, jni, resources...
- Currently, all the classes which are needed to put on configuration files are: 102 classes in Kompile, and many libraries (google, javacpp, jdk, org.nd4j, org.bytedeco, konduit).
- Right now, the configuration files are manually built, and they contain many redundant classes which are not needed in the build process.
- Therefore, we plan to have a "tool" which will generate these configuration files with two objectives: automatic and efficient.

## Proposal
1. For classes inside Kompile, define our own annotation. Create a configuration file and add all needed classes so that the agent can include them into .json configuration files. 
   - Define the @ReflectionConfig annotation. Create GraalConfig class to contain all the reflection configs. \
   &nbsp;&nbsp;&nbsp;&nbsp;@ReflectionConfig( \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;type = StringReverser.class, \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;methods = { \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;@ReflectionConfig.ReflectiveMethodConfig(name = "reverse", parameterTypes = {String.class}) \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;} \
   &nbsp;&nbsp;&nbsp;&nbsp;) \
   &nbsp;&nbsp;&nbsp;&nbsp;@ReflectionConfig( \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;type = StringCapitalizer.class, \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;methods = { \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;@ReflectionConfig.ReflectiveMethodConfig(name = "capitalize", parameterTypes = {String.class}) \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;} \
   &nbsp;&nbsp;&nbsp;&nbsp;) \
   &nbsp;&nbsp;&nbsp;&nbsp;class GraalConfig{}\
   The agent will scan the Configuration class then find the configurations which are marked with our annotation and insert data in .json file.
2. For libraries: Firstly, write our own Java Agent (or use Tracing Agent if we can get the source code). Secondly, define a test suit (or something like that), to run all the Kompile APIs with the java agent and generate .json configuration files. 
   - Define an interface: \
   &nbsp;&nbsp;&nbsp;&nbsp;public interface ConfigurationGenerator { \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;void generate(); \
   &nbsp;&nbsp;&nbsp;&nbsp;} 
   - Define classes which implement above interface: \
   &nbsp;&nbsp;&nbsp;&nbsp;public class GenerateImageAndSDKConfigurationGenerator implements ConfigurationGenerator{ \
   &nbsp;&nbsp;&nbsp;&nbsp;@Autowired \
   &nbsp;&nbsp;&nbsp;&nbsp;NativeImageAgent nativeImageAgent; \
   &nbsp;&nbsp;&nbsp;&nbsp;@Autowired \
   &nbsp;&nbsp;&nbsp;&nbsp;GenerateImageAndSDK generateImageAndSDKCommand; \
   &nbsp;&nbsp;&nbsp;&nbsp;@Override \
   &nbsp;&nbsp;&nbsp;&nbsp;public void generate() { \
   &nbsp;&nbsp;&nbsp;&nbsp; &nbsp;&nbsp;&nbsp;&nbsp;// TODO how to use NativeImageAgent \
   &nbsp;&nbsp;&nbsp;&nbsp; &nbsp;&nbsp;&nbsp;&nbsp;generateImageAndSDKCommand.call(); \
   &nbsp;&nbsp;&nbsp;&nbsp;} \
   } \
   - Define a main class: In this class, The main method will load all the classes which extends ConfigurationGenerator run them with the agent embedded, check if any classes which use reflection or jni, then insert data in .json files. \
   &nbsp;&nbsp;&nbsp;&nbsp;public class MainConfigurationGenerator { \
   &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;public static List<ConfigurationGenerator> configurationGenerators;\
     &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;@Autowired \
     &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;public MainConfigurationGenerator(List<ConfigurationGenerator> configurationGenerators) { \
     &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;this.configurationGenerators = configurationGenerators; \
     &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;} \
     &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;public static void main(String[] args) { \
     &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;configurationGenerators.forEach(ConfigurationGenerator::generate); \
     &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;} \
     &nbsp;&nbsp;&nbsp;&nbsp;}
## Consequences
### Advantages
- The process of generating configuration files is automatic. Each time we have changes in Konduit/Dl4j, we don't have to manually editing the .json configuration files.
- We just include only the classes and methods which will be used to run the CLI, so the native image size will be decreased.

### Disadvantages
- Required write all the cases of the test suite. It's somehow impossible to have code coverage 100%.