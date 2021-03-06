package com.pipeline;

public class ExecutorConfigGrammar implements IConfigGrammar {
    enum ConfigParams {
        BUFFER_SIZE ("BUFFER_SIZE"),
        MODE ("MODE");

        private final String str;
        ConfigParams(String str) {
            this.str = str;
        }

        public String toStr() {
            return str;
        }
    }

    @Override
    public boolean hasKey(String key) {
        for (ConfigParams param : ConfigParams.values()) {
            if (key.equalsIgnoreCase(param.toStr()))
                return true;
        }
        return false;
    }
}
