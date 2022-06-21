package ai.konduit.pipelinegenerator.main.util;

public class SplitGraalDeclarations {

    public static void main(String...args) {
        String[] split = args[0].split("--trace-class-initialization=");
        String[] classNames = split[1].split(",");
        for(String split1 : classNames) {
            System.out.println(String.format("        stringBuilder.append(\"--initialize-at-build-time=%s\\n\");\n",split1));

        }
    }

}
