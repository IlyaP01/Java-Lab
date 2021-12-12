package com.pipeline;

import com.java_polytech.pipeline_interfaces.*;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Manager implements IConfigurable {
    IReader reader;
    ArrayList<IExecutor> executors = new ArrayList<>();
    String executorsSeparator = ",";
    String configsSeparator = ",";
    IWriter writer;
    FileInputStream fin;
    FileOutputStream fout;
    private static Logger logger;

    private RC openStreams(ConfigReader configReader) {
        try {
            fin = new FileInputStream(configReader.getParam(ManagerConfigGrammar.ConfigParams.INPUT_FILE.toStr()));
        } catch (FileNotFoundException e) {
            return RC.RC_MANAGER_INVALID_INPUT_FILE;
        }

        try {
            fout = new FileOutputStream(configReader.getParam(ManagerConfigGrammar.ConfigParams.OUTPUT_FILE.toStr()));
        } catch (FileNotFoundException e) {
            return RC.RC_MANAGER_INVALID_OUTPUT_FILE;
        }

        return RC.RC_SUCCESS;
    }

    private RC findClasses(ConfigReader configReader) {
        String key = ManagerConfigGrammar.ConfigParams.READER_NAME.toStr();
        try {
            Class<?> ReaderClass = Class.forName(configReader.getParam(key));
            if (IReader.class.isAssignableFrom(ReaderClass))
                reader = (IReader)ReaderClass.getDeclaredConstructor().newInstance();
            else
                return RC.RC_MANAGER_INVALID_READER_CLASS;

        } catch (Exception e) {
            return RC.RC_MANAGER_INVALID_READER_CLASS;
        }

        key = ManagerConfigGrammar.ConfigParams.EXECUTORS_NAMES.toStr();
        String executorsStr = configReader.getParam(key);
        String[] executorsNamesArr = executorsStr.split(executorsSeparator);
        if (executorsNamesArr.length < 1)
            return RC.RC_MANAGER_CONFIG_SEMANTIC_ERROR;
        for (String executor : executorsNamesArr) {
            executor = executor.trim();
            try {
                Class<?> ExecutorClass = Class.forName(executor);
                if (IExecutor.class.isAssignableFrom(ExecutorClass))
                    executors.add((IExecutor)ExecutorClass.getDeclaredConstructor().newInstance());
                else
                    return RC.RC_MANAGER_INVALID_EXECUTOR_CLASS;

            } catch (Exception e) {
                return RC.RC_MANAGER_INVALID_EXECUTOR_CLASS;
            }
        }

        key = ManagerConfigGrammar.ConfigParams.WRITER_NAME.toStr();
        try {
            Class<?> WriterClass = Class.forName(configReader.getParam(key));
            if (IWriter.class.isAssignableFrom(WriterClass))
                writer = (IWriter) WriterClass.getDeclaredConstructor().newInstance();
            else
                return RC.RC_MANAGER_INVALID_WRITER_CLASS;

        } catch (Exception e) {
            return RC.RC_MANAGER_INVALID_WRITER_CLASS;
        }

        return RC.RC_SUCCESS;
    }

    private RC setWorkersConfigs(ConfigReader configReader) {
        RC rc = reader.setConfig(configReader.getParam(ManagerConfigGrammar.ConfigParams.READER_CONFIG.toStr()));
        if (!rc.isSuccess())
            return rc;

        String configsStr = configReader.getParam(ManagerConfigGrammar.ConfigParams.EXECUTORS_CONFIGS.toStr());
        String[] configs = configsStr.split(configsSeparator);
        if (configs.length != executors.size())
            return RC.RC_MANAGER_CONFIG_SEMANTIC_ERROR;
        for (int i = 0; i < configs.length; ++i) {
            String config = configs[i].trim();
            rc = executors.get(i).setConfig(config);
            if (!rc.isSuccess())
                return rc;
        }

        rc = writer.setConfig(configReader.getParam(ManagerConfigGrammar.ConfigParams.WRITER_CONFIG.toStr()));

        return rc;
    }

    private RC configureWorkers() {
        RC rc = reader.setInputStream(fin);
        if (!rc.isSuccess())
            return rc;

        rc = writer.setOutputStream(fout);
        if (!rc.isSuccess())
            return rc;

        rc = reader.setConsumer(executors.get(0));
        if (!rc.isSuccess())
            return rc;

        for (int i = 0; i < executors.size() - 1; ++i) {
            rc = executors.get(i).setConsumer(executors.get(i + 1));
            if (!rc.isSuccess())
                return rc;
        }
        rc = executors.get(executors.size() - 1).setConsumer(writer);
        if (!rc.isSuccess())
            return rc;

        return RC.RC_SUCCESS;
    }

    @Override
    public RC setConfig(String s) {
        ConfigReader configReader = new ConfigReader(RC.RCWho.MANAGER, new ManagerConfigGrammar());
        RC rc = configReader.read(s);
        if (!rc.isSuccess())
            return rc;

        try {
            logger = Logger.getLogger("logger");
            String logFileParam =  ManagerConfigGrammar.ConfigParams.LOG_FILE.toStr();
            FileHandler fileHandler = new FileHandler(configReader.getParam(logFileParam));
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);
        } catch (IOException ignored) {}

        for (ManagerConfigGrammar.ConfigParams param : ManagerConfigGrammar.ConfigParams.values()) {
            if (!configReader.hasKey(param.toStr()))
                return new RC(RC.RCWho.MANAGER,
                        RC.RCType.CODE_CONFIG_SEMANTIC_ERROR,
                        "Config must contain parameter " + param.toStr());
        }

        rc = findClasses(configReader);
        if (!rc.isSuccess())
            return rc;

        rc = openStreams(configReader);
        if (!rc.isSuccess())
            return rc;

        rc = setWorkersConfigs(configReader);
        if (!rc.isSuccess())
            return rc;

        rc = configureWorkers();
        if (!rc.isSuccess())
            return rc;

        return RC.RC_SUCCESS;
    }

    public RC runPipeline() {
        RC rc = reader.run();
        try {
            fin.close();
            fout.close();
        } catch (IOException e) {
           handleError(new RC(RC.RCWho.MANAGER, RC.RCType.CODE_CUSTOM_ERROR, "Could not close files"));
        }
        return rc;
    }

    static public void handleError(RC rc) {
        String errMsg = "Error in " + rc.who + ": " + rc.info;
        System.out.println(errMsg);
        if (logger != null)
            logger.severe(errMsg);
        else
            System.out.println("Logger is not initialized");
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            handleError(RC.RC_MANAGER_INVALID_ARGUMENT);
            return;
        }
        Manager manager = new Manager();
        RC rc = manager.setConfig(args[0]);
        if (!rc.isSuccess()) {
            handleError(rc);
            return;
        }

        rc = manager.runPipeline();
        if (!rc.isSuccess())
            handleError(rc);
        else {
            System.out.println("Success!");
        }
    }
}
