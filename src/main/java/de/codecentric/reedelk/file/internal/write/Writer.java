package de.codecentric.reedelk.file.internal.write;

import de.codecentric.reedelk.file.component.FileWrite;
import de.codecentric.reedelk.file.internal.attribute.FileAttribute;
import de.codecentric.reedelk.file.internal.commons.CloseableUtils;
import de.codecentric.reedelk.file.internal.commons.FileChannelProvider;
import de.codecentric.reedelk.file.internal.exception.FileWriteException;
import de.codecentric.reedelk.file.internal.exception.MaxRetriesExceeded;
import de.codecentric.reedelk.file.internal.exception.NotValidFileException;
import de.codecentric.reedelk.runtime.api.component.OnResult;
import de.codecentric.reedelk.runtime.api.flow.FlowContext;
import de.codecentric.reedelk.runtime.api.message.Message;
import de.codecentric.reedelk.runtime.api.message.MessageBuilder;
import de.codecentric.reedelk.runtime.api.message.content.TypedPublisher;
import de.codecentric.reedelk.file.internal.commons.Messages;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static de.codecentric.reedelk.runtime.api.commons.StackTraceUtils.rootCauseMessageOf;

public class Writer {

    public void write(WriteConfiguration config, FlowContext flowContext, OnResult callback,
                      Path path, TypedPublisher<byte[]> dataStream) {

        int bufferLength = config.getWriteBufferSize();

        Flux.from(dataStream)
                // This data stream is executed from originator Thread (which could be nio Thread or flow thread and so on).
                // Since we MUST execute this asynchronously (otherwise we might end up blocking a nio Thread - e.g from a rest call -
                // we must subscribe the stream from an elastic Thread. If we don't do it we might block
                // the NIO thread indefinitely. Note that the reduceWith is executed within the publishOn thread,
                // while the accumulator BiFunction is executed within the source Thread (e.g NIO thread).
                // The success callback is executed within the source Thread, while the onError from the elastic thread.

                .reduceWith(() -> {
                    try {
                        FileChannel fileChannel = FileChannelProvider.from(path,
                                config.getLockType(),
                                config.getRetryMaxAttempts(),
                                config.getRetryWaitTime(),
                                config.getWriteMode().options());

                        // We only allocate the buffer object if the FileChannel
                        // has been correctly opened.
                        ByteBuffer byteBuffer = ByteBuffer.allocate(bufferLength);
                        return new Initial(byteBuffer, fileChannel);

                    } catch (Exception e) {
                        throw Exceptions.propagate(e);
                    }

                }, (initial, byteChunk) -> {

                    try {

                        // Write it in multiple steps
                        int remaining;
                        int offset = 0;
                        int length = Math.min(byteChunk.length, bufferLength);

                        while (length > 0) {

                            initial.buffer.clear();

                            initial.buffer.put(byteChunk, offset, length);

                            initial.buffer.flip();

                            initial.fileChannel.write(initial.buffer);

                            offset += bufferLength;

                            remaining = byteChunk.length - offset;

                            length = Math.min(remaining, bufferLength);

                        }

                        return initial;

                    } catch (Exception e) {
                        // Do on success or error is not called if an exception
                        // occurred here. Therefore we MUST clean up byte buffer
                        // AND close file channel here.
                        cleanUp(initial);
                        throw Exceptions.propagate(e);
                    }


                }).doOnSuccessOrError((initial, throwable) -> {

            // We must always and in any case (success or error) close the file channel.
            cleanUp(initial);

        }).doOnError(throwable -> {

            // On error map the exception and invoke the error callback.
            Exception realException = mapException(path, throwable);
            callback.onError(flowContext, realException);

        }).doOnSuccess(initial -> {

            // On success build the message and invoke the callback.
            FileAttribute attributes = new FileAttribute(path.toString());

            Message outMessage = MessageBuilder.get(FileWrite.class)
                    .attributes(attributes)
                    .empty()
                    .build();

            callback.onResult(flowContext, outMessage);

        }).subscribeOn(Schedulers.elastic())
                .subscribe(); // Immediately fire the writing into the buffer
    }

    private Exception mapException(Path path, Throwable throwable) {
        if (throwable instanceof NoSuchFileException) {
            String message = Messages.FileWrite.ERROR_FILE_NOT_FOUND.format(path.toString());
            return new NotValidFileException(message, throwable);

        } else if (throwable instanceof MaxRetriesExceeded) {
            String message = Messages.Misc.FILE_LOCK_MAX_RETRY_ERROR.format(path.toString(), rootCauseMessageOf(throwable));
            return new FileWriteException(message, throwable);

        } else if (throwable instanceof FileAlreadyExistsException) {
            String message = Messages.FileWrite.ERROR_FILE_WRITE_ALREADY_EXISTS.format(path.toString());
            return new FileWriteException(message, throwable);

        } else {
            String errorMessage = Messages.FileWrite.ERROR_FILE_WRITE_WITH_PATH.format(path.toString(), rootCauseMessageOf(throwable));
            return new FileWriteException(errorMessage, throwable);
        }
    }

    private void cleanUp(Initial initial) {
        if (initial != null) {
            if (initial.buffer != null) initial.buffer.clear();
            initial.buffer = null;

            CloseableUtils.closeSilently(initial.fileChannel);
            initial.fileChannel = null;
        }
    }

    /**
     * An object keeping a pair of buffer and file channel used in the reduce step
     * to avoid creating a buffer before knowing if the file channel could be successfully
     * opened.
     */
    static class Initial {
        ByteBuffer buffer;
        FileChannel fileChannel;

        Initial(ByteBuffer buffer, FileChannel fileChannel) {
            this.buffer = buffer;
            this.fileChannel = fileChannel;
        }
    }
}
