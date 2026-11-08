package know.engine;

import know.engine.rag.KnowEngineQueryTransformer;
import org.springframework.beans.BeansException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

@SpringBootApplication
public class KnowEngineApplication implements ApplicationContextAware{

    public static void main(String[] args) {
        SpringApplication.run(KnowEngineApplication.class, args);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        KnowEngineQueryTransformer.setApplicationContext(applicationContext);
    }
}
