package com.pipeline;

import com.java_polytech.pipeline_interfaces.*;

import java.util.Arrays;

public class Executor implements IExecutor {
    private interface ICoder {
        RC run(byte[] bytes);
    }

    private class Encoder implements  ICoder {
        private byte prevByte = 0;
        private byte repeatingByte = 0;
        private byte counter = 0;
        private int startOfSingles = 0;

        // helper method
        // set counter, repeatingByte and startOfSingles values when new sequence of
        // the same or different symbols starts
        private int startWith(int i, byte[] bytes) {
            if (i >= bytes.length) {
                counter = 0;
                return i;
            }

            if (i + 1 == bytes.length) {
                counter = 1;
                repeatingByte = bytes[i];
                return i + 1;
            }

            if (bytes[i + 1] == bytes[i]) {
                counter = 2;
                repeatingByte = bytes[i + 1];
            }
            else {
                counter = -2;
                startOfSingles = i;
            }

            prevByte = bytes[i + 1];

            return i + 2;
        }

        public RC run(byte[] bytes) {
            if (bytes == null)
                return RC.RC_SUCCESS;
            int i = startWith(0, bytes);
            for (; i < bytes.length; ++i) {
                if (counter == Byte.MAX_VALUE) {
                    RC rc = writeToBuffer(counter);
                    if (!rc.isSuccess())
                        return rc;
                    rc = writeToBuffer(repeatingByte);
                    if (!rc.isSuccess())
                        return rc;
                    i = startWith(i, bytes);
                }
                if (counter == Byte.MIN_VALUE) {
                    RC rc = writeToBuffer(counter);
                    if (!rc.isSuccess())
                        return rc;
                    rc = writeToBuffer(bytes, startOfSingles, -counter);
                    if (!rc.isSuccess())
                        return rc;
                    i = startWith(i, bytes);
                }

                if (i >= bytes.length)
                    break;

                if (bytes[i] == prevByte) {
                    if (counter > 0)
                        ++counter;
                    else {
                        RC rc = writeToBuffer((byte) (counter + 1));
                        if (!rc.isSuccess())
                            return rc;
                        rc = writeToBuffer(bytes, startOfSingles, -counter - 1);
                        if (!rc.isSuccess())
                            return rc;
                        counter = 2;
                        repeatingByte = bytes[i];
                    }
                }
                else {
                    if (counter > 0) {
                        RC rc = writeToBuffer(counter);
                        if (!rc.isSuccess())
                            return rc;
                        rc = writeToBuffer(repeatingByte);
                        if (!rc.isSuccess())
                            return rc;
                        i = startWith(i, bytes) - 1;
                    }
                    else {
                        --counter;
                    }
                }
                prevByte = bytes[i];
            }

            if (counter > 0) {
                RC rc = writeToBuffer(counter);
                if (!rc.isSuccess())
                    return rc;
                rc = writeToBuffer(repeatingByte);
                if (!rc.isSuccess())
                    return rc;
            }
            else if (counter < 0) {
                RC rc = writeToBuffer(counter);
                if (!rc.isSuccess())
                    return rc;
                rc = writeToBuffer(bytes, startOfSingles, -counter);
                if (!rc.isSuccess())
                    return rc;
            }

            return RC.RC_SUCCESS;
        }
    }

    private class Decoder implements ICoder {
        // if the sequence of different symbols to decode is taller than current input buffer
        private int restToDecode = 0;
        // if there is a count of the end of input buffer and encoded symbol if next buffer
        private int prevCount = 0;

        @Override
        public RC run(byte[] bytes) {
            /* if algorithm was written correctly then we must
           write the rest of different symbols from previous buffer
           or the first symbol prevCount times, but not at the same time */
            assert(prevCount >= 0 && restToDecode == 0 ||
                    prevCount == 0 && restToDecode >= 0);

            if (bytes == null) {
                if (restToDecode != 0 || prevCount != 0)
                    return new RC(RC.RCWho.EXECUTOR, RC.RCType.CODE_CUSTOM_ERROR, "Invalid RLE code");
                return RC.RC_SUCCESS;
            }
            int i = 0;
            if (prevCount > 0) {
                for (int j = 0; j < prevCount; ++j) {
                    RC rc = writeToBuffer(bytes[0]);
                    if (!rc.isSuccess())
                        return rc;
                }
                ++i;
                prevCount = 0;
            }
            else if (restToDecode > 0) {
                int restInThisBuffer = Integer.min(bytes.length, restToDecode);
                RC rc = writeToBuffer(bytes, 0, restInThisBuffer);
                if (!rc.isSuccess())
                    return rc;
                i += restInThisBuffer;
                restToDecode -= restInThisBuffer;
            }

            while (i < bytes.length)  {
                int count = bytes[i];
                if (i + 1 == bytes.length) {
                    restToDecode = count > 0 ? 0 : -count;
                    prevCount =  Integer.max(count, 0);
                    return RC.RC_SUCCESS;
                }
                if (count > 0) {
                    byte sym =  bytes[i + 1];
                    for (int j = 0; j < count; ++j) {
                        RC rc = writeToBuffer(sym);
                        if (!rc.isSuccess())
                            return rc;
                    }
                    i += 2;
                }
                else {
                    count = -count;
                    ++i;
                    if (count > bytes.length - i) {
                        restToDecode = count - (bytes.length - i);
                        count = bytes.length - i;
                    }
                    RC rc = writeToBuffer(bytes, i, count);
                    if (!rc.isSuccess())
                        return rc;
                    i += count;
                }
            }

            return RC.RC_SUCCESS;
        }
    }

    private enum Mode {
        ENCODE ("ENCODE"),
        DECODE ("DECODE");
        private final String str;

        Mode(String str) {
            this.str = str;
        }
        String toStr() {
            return str;
        }
    }

    private IConsumer writer;
    private byte[] buffer;
    private int bufferSize;
    private int bufferIndex = 0;
    private ICoder coder;
    private IMediator mediator;
    final private TYPE[] supportedTypes = { TYPE.BYTE_ARRAY };

    @Override
    public RC setConfig(String s) {
        ConfigReader configReader = new ConfigReader(RC.RCWho.EXECUTOR, new ExecutorConfigGrammar());
        RC rc = configReader.read(s);
        if (!rc.isSuccess())
            return rc;

        if (!configReader.hasKey(ExecutorConfigGrammar.ConfigParams.BUFFER_SIZE.toStr())) {
            return RC.RC_EXECUTOR_CONFIG_SEMANTIC_ERROR;
        }

        String sizeStr = configReader.getParam(ExecutorConfigGrammar.ConfigParams.BUFFER_SIZE.toStr());
        try {
            bufferSize = Integer.parseInt(sizeStr);
        }
        catch (NumberFormatException e) {
            return RC.RC_EXECUTOR_CONFIG_SEMANTIC_ERROR;
        }

        if (bufferSize <= 0)
            return RC.RC_EXECUTOR_CONFIG_SEMANTIC_ERROR;

        buffer = new byte[bufferSize];

        if (!configReader.hasKey(ExecutorConfigGrammar.ConfigParams.MODE.toStr())) {
            return RC.RC_EXECUTOR_CONFIG_SEMANTIC_ERROR;
        }
        String modeStr = configReader.getParam(ExecutorConfigGrammar.ConfigParams.MODE.toStr());
        if (modeStr.equalsIgnoreCase(Mode.ENCODE.toStr()))
            coder = new Encoder();
        else if (modeStr.equalsIgnoreCase(Mode.DECODE.toStr()))
            coder = new Decoder();
        else
            return RC.RC_EXECUTOR_CONFIG_SEMANTIC_ERROR;

       return RC.RC_SUCCESS;
    }

    private RC writeToBuffer(byte val) {
        buffer[bufferIndex] = val;
        bufferIndex++;
        if (bufferIndex == bufferSize) {
            RC rc = writer.consume();
            bufferIndex = 0;
            return rc;
        }
        return RC.RC_SUCCESS;
    }

    private RC writeToBuffer(byte[] bytes, int from, int count) {
        for (int i = from, cnt = 0; cnt < count; ++cnt, ++i) {
            RC rc = writeToBuffer(bytes[i]);
            if (!rc.isSuccess())
                return rc;
        }
        return RC.RC_SUCCESS;
    }

    @Override
    public RC setProvider(IProvider iProvider) {
        boolean isEmptyIntersect = true;
        TYPE intersectType = null;
        outerLoop: for (TYPE myType : supportedTypes) {
            for (TYPE providerType : iProvider.getOutputTypes()) {
                if (myType == providerType) {
                    intersectType = myType;
                    isEmptyIntersect = false;
                    break outerLoop;
                }
            }
        }
        if (isEmptyIntersect)
            return RC.RC_EXECUTOR_TYPES_INTERSECTION_EMPTY_ERROR;

        mediator = iProvider.getMediator(intersectType);
        return RC.RC_SUCCESS;
    }

    @Override
    public RC consume() {
        byte[] data = (byte[]) mediator.getData();
        RC rc = coder.run(data);
        if (!rc.isSuccess())
            return rc;
        if (data == null) {
            if (bufferIndex != 0) {
                rc = writer.consume();
                if (!rc.isSuccess())
                    return rc;
            }
            bufferIndex = 0;
            return writer.consume();
        }

        return RC.RC_SUCCESS;
    }

    @Override
    public RC setConsumer(IConsumer iConsumer) {
        writer = iConsumer;
        return iConsumer.setProvider(this);
    }

    @Override
    public TYPE[] getOutputTypes() {
        return supportedTypes;
    }

    @Override
    public IMediator getMediator(TYPE type) {
        if (type == TYPE.BYTE_ARRAY) {
            class ByteArrayMediator implements IMediator {
                @Override
                public Object getData() {
                    return bufferIndex > 0 ? Arrays.copyOf(buffer, bufferIndex) : null;
                }
            }
            return new ByteArrayMediator();
        }
        else
            return null;
    }

}
