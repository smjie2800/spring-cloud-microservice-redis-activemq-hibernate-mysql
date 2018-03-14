package com.hzg.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

@Component
public class Writer {

    static Logger logger = Logger.getLogger(Writer.class);

    public Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();

    /**
     * 以JSON格式输出
     * @param response
     */
    public void writeObjectToJson(HttpServletResponse response, Object object) {
        String json;
        if (object != null) {
            json = gson.toJson(object);
        } else {
            json = "{}";
        }

        if (json.contains("\"password\"")) {
            json = json.replaceAll("\"password\":\"\\w*\"", "\"password\":\"\"");
        }
        writeStringToJson(response, json);
    }

    public void writeStringToJson(HttpServletResponse response, String string) {
        logger.info("the string to response:" + string);

        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=utf-8");
        PrintWriter out = null;
        try {
            out = response.getWriter();
            out.print(string);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }
}
