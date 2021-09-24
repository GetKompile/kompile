package ai.konduit.pipelinegenerator.main;

import java.util.List;
import java.util.Map;
import java.util.Properties;

public class PomListConfig {

    private String singleName,listName;
    private List<String> values;
    private Properties props;
    private String propertyListName;


    public PomListConfig(String singleName, String listName, List<String> values) {
        this(singleName,listName,values, new Properties(),null);
    }

    public PomListConfig(String singleName, String listName, List<String> values, Properties props,String propertyListName) {
        this.singleName = singleName;
        this.listName = listName;
        this.values = values;
        this.propertyListName = propertyListName;
        this.props = props;
    }



    public String toConfigString() {
        StringBuilder stringBuilder = new StringBuilder();
        if(listName != null) {
            stringBuilder.append("<" + listName + ">\n");
            for (String item : values) {
                stringBuilder.append("<" + singleName + ">" + item + "</" + singleName + ">\n");
            }

            stringBuilder.append("</" + listName + ">\n");
        }
        if(propertyListName != null) {
            stringBuilder.append("<" + propertyListName + ">\n");
            for(Map.Entry<Object,Object> prop : props.entrySet()) {
                stringBuilder.append("<property>\n");
                stringBuilder.append("<name>" + prop.getKey()+ "</name>\n");
                stringBuilder.append("<value>" + prop.getValue() + "</value>\n");
                stringBuilder.append("</property>\n");
            }

            stringBuilder.append("</" + propertyListName + ">\n");
        }

        return stringBuilder.toString();
    }

}
