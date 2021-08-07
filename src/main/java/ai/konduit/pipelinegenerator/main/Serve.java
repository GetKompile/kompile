package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.util.ObjectMappers;
import ai.konduit.serving.vertx.api.DeployKonduitServing;
import ai.konduit.serving.vertx.config.InferenceConfiguration;
import ai.konduit.serving.vertx.config.InferenceDeploymentResult;
import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.VertxOptions;
import org.nd4j.shade.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "serve",mixinStandardHelpOptions = true)
public class Serve implements Callable<Integer> {
    @CommandLine.Option(names = {"--pipeline"},description = "Pipeline String",required = true)
    private File pipeline;
    private ObjectMapper jsonMapper = ai.konduit.serving.pipeline.util.ObjectMappers.json();
    private ObjectMapper yamlMapper = ai.konduit.serving.pipeline.util.ObjectMappers.yaml();

    @Override
    public Integer call() throws Exception {
        InferenceConfiguration inferenceConfiguration;
        if(pipeline.getName().endsWith(".json")) {
            inferenceConfiguration = jsonMapper.readValue(pipeline,InferenceConfiguration.class);
        } else if(pipeline.getName().equals(".yml") || pipeline.getName().endsWith(".yaml")) {
            inferenceConfiguration = yamlMapper.readValue(pipeline,InferenceConfiguration.class);
        } else {
            System.err.println("Wrong file specified. Must either be json, yml, or yaml.");
            return 1;
        }

        DeployKonduitServing.deploy(new VertxOptions(), new DeploymentOptions(), inferenceConfiguration, new Handler<AsyncResult<InferenceDeploymentResult>>() {
            @Override
            public void handle(AsyncResult<InferenceDeploymentResult> inferenceDeploymentResultAsyncResult) {
                        if(inferenceDeploymentResultAsyncResult.failed()) {
                            inferenceDeploymentResultAsyncResult.cause().printStackTrace();
                        } else {
                            System.out.println("Deployed server");
                        }
            }
        });
        return 0;
    }
}
