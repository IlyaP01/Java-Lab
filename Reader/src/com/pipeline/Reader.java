package com.pipeline;

import com.java_polytech.pipeline_interfaces.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class Reader implements IReader {
    InputStream input;
    IConsumer consumer;
    private byte[] buffer;
    private int bufferSize;
    final private TYPE[] supportedTypes = { TYPE.BYTE_ARRAY, TYPE.CHAR_ARRAY, TYPE.INT_ARRAY };
    private int sizeOfData;

    @Override
    public RC setInputStream(InputStream inputStream) {
        input = inputStream;
        return RC.RC_SUCCESS;
    }

    @Override
    public RC run() {
        try {
            sizeOfData = input.read(buffer, 0, bufferSize);
        } catch (IOException e) {
            return RC.RC_READER_FAILED_TO_READ;
        }

        while (sizeOfData > 0) {
            RC rc = consumer.consume();
            if (!rc.isSuccess()) {
                sizeOfData = 0;
                consumer.consume();
                return rc;
            }
            try {
                sizeOfData = input.read(buffer, 0, bufferSize);
            } catch (IOException e) {
                return RC.RC_READER_FAILED_TO_READ;
            }
        }

        sizeOfData = 0;
        return consumer.consume();
    }

    @Override
    public RC setConfig(String s) {
        ConfigReader configReader = new ConfigReader(RC.RCWho.READER, new ReaderConfigGrammar());
        RC rc = configReader.read(s);
        if (!rc.isSuccess())
            return rc;

        if (!configReader.hasKey(ReaderConfigGrammar.ConfigParams.BUFFER_SIZE.toStr())) {
            return RC.RC_READER_CONFIG_SEMANTIC_ERROR;
        }

        String sizeStr = configReader.getParam(ReaderConfigGrammar.ConfigParams.BUFFER_SIZE.toStr());
        try {
            bufferSize = Integer.parseInt(sizeStr);
        }
        catch (NumberFormatException e) {
            return RC.RC_READER_CONFIG_SEMANTIC_ERROR;
        }

        if (bufferSize <= 0)
            return RC.RC_READER_CONFIG_SEMANTIC_ERROR;

        buffer = new byte[bufferSize];

        return RC.RC_SUCCESS;
    }

    @Override
    public RC setConsumer(IConsumer iConsumer) {
        consumer = iConsumer;
        return iConsumer.setProvider(this);
    }

    @Override
    public TYPE[] getOutputTypes() {
        return supportedTypes;
    }

    @Override
    public IMediator getMediator(TYPE type) {
        if (type == TYPE.BYTE_ARRAY) {
            class ByteArrayMediator implements  IMediator {
                @Override
                public Object getData() {
                    return sizeOfData > 0 ? Arrays.copyOf(buffer, sizeOfData) : null;
                }
            }
            return new ByteArrayMediator();
        }
        else if (type == TYPE.CHAR_ARRAY) {
            class CharArrayMediator implements  IMediator {
                @Override
                public Object getData() {
                    assert(bufferSize % 2 == 0);
                    if (sizeOfData == 0)
                        return null;
                    ByteBuffer b = ByteBuffer.wrap(Arrays.copyOf(buffer, sizeOfData));
                    return b.asCharBuffer().array();
                }
            }
            return new CharArrayMediator();
        }
        else if (type == TYPE.INT_ARRAY) {
            class IntArrayMediator implements IMediator {
                @Override
                public Object getData() {
                    assert(bufferSize % 4 == 0);
                    if (sizeOfData == 0)
                        return null;
                    ByteBuffer b = ByteBuffer.wrap(Arrays.copyOf(buffer, sizeOfData));
                    return b.asIntBuffer().array();
                }
            }
            return new IntArrayMediator();
        }
        else
            return null;
    }
}
