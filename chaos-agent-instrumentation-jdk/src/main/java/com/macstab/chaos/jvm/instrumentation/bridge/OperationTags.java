package com.macstab.chaos.jvm.instrumentation.bridge;

/**
 * The operation names the woven advice passes to {@link BootstrapDispatcher}.
 *
 * <p>The bridge runs in the bootstrap class loader, which cannot see the API's {@code
 * OperationType}, so operations cross it by name. These constants make a misspelled name a compile
 * error instead of a failure on the first intercepted call; they are inlined where used. Each is
 * the name of an {@code OperationType} constant, except {@link #FILE_IO_TRANSFER_TO} and {@link
 * #FILE_IO_TRANSFER_FROM}, the file-channel transfers the core maps to reads and writes, and {@link
 * #SCHEDULE_SUBMIT_PERIODIC}, a periodic {@code SCHEDULE_SUBMIT}.
 */
public final class OperationTags {

  private OperationTags() {}

  /** {@code ASYNC_COMPLETE}. */
  public static final String ASYNC_COMPLETE = "ASYNC_COMPLETE";

  /** {@code ASYNC_COMPLETE_EXCEPTIONALLY}. */
  public static final String ASYNC_COMPLETE_EXCEPTIONALLY = "ASYNC_COMPLETE_EXCEPTIONALLY";

  /** {@code AWS_SDK_CALL}. */
  public static final String AWS_SDK_CALL = "AWS_SDK_CALL";

  /** {@code DATAGRAM_RECEIVE}. */
  public static final String DATAGRAM_RECEIVE = "DATAGRAM_RECEIVE";

  /** {@code DATAGRAM_SEND}. */
  public static final String DATAGRAM_SEND = "DATAGRAM_SEND";

  /** {@code DNS_REVERSE}. */
  public static final String DNS_REVERSE = "DNS_REVERSE";

  /** {@code EXECUTOR_AWAIT_TERMINATION}. */
  public static final String EXECUTOR_AWAIT_TERMINATION = "EXECUTOR_AWAIT_TERMINATION";

  /** {@code EXECUTOR_SHUTDOWN}. */
  public static final String EXECUTOR_SHUTDOWN = "EXECUTOR_SHUTDOWN";

  /**
   * {@code SCHEDULE_SUBMIT} of a periodic schedule ({@code scheduleAtFixedRate} / {@code
   * scheduleWithFixedDelay}); the core maps it to {@code SCHEDULE_SUBMIT} with {@code periodic}
   * set.
   */
  public static final String SCHEDULE_SUBMIT_PERIODIC = "SCHEDULE_SUBMIT_PERIODIC";

  /** {@code EXECUTOR_SUBMIT}. */
  public static final String EXECUTOR_SUBMIT = "EXECUTOR_SUBMIT";

  public static final String HTTP_CLIENT_SEND = "HTTP_CLIENT_SEND";

  public static final String HTTP_CLIENT_SEND_ASYNC = "HTTP_CLIENT_SEND_ASYNC";

  public static final String JDBC_CONNECTION_ACQUIRE = "JDBC_CONNECTION_ACQUIRE";

  /** {@code JMX_GET_ATTR}. */
  public static final String JMX_GET_ATTR = "JMX_GET_ATTR";

  /** {@code FILE_IO_CLOSE}. */
  public static final String FILE_IO_CLOSE = "FILE_IO_CLOSE";

  /** {@code FILE_IO_DELETE}. */
  public static final String FILE_IO_DELETE = "FILE_IO_DELETE";

  /** {@code FILE_IO_MAP}. */
  public static final String FILE_IO_MAP = "FILE_IO_MAP";

  /** {@code FILE_IO_MOVE}. */
  public static final String FILE_IO_MOVE = "FILE_IO_MOVE";

  /** {@code FILE_IO_MOVE_TARGET}. */
  public static final String FILE_IO_MOVE_TARGET = "FILE_IO_MOVE_TARGET";

  /** {@code FILE_IO_OPEN}. */
  public static final String FILE_IO_OPEN = "FILE_IO_OPEN";

  /** {@code FILE_IO_READ}. */
  public static final String FILE_IO_READ = "FILE_IO_READ";

  /** {@code FILE_IO_SYNC}. */
  public static final String FILE_IO_SYNC = "FILE_IO_SYNC";

  /** {@code FILE_IO_TRANSFER_FROM}. */
  public static final String FILE_IO_TRANSFER_FROM = "FILE_IO_TRANSFER_FROM";

  /** {@code FILE_IO_TRANSFER_TO}. */
  public static final String FILE_IO_TRANSFER_TO = "FILE_IO_TRANSFER_TO";

  /** {@code FILE_IO_TRUNCATE}. */
  public static final String FILE_IO_TRUNCATE = "FILE_IO_TRUNCATE";

  /** {@code FILE_IO_WRITE}. */
  public static final String FILE_IO_WRITE = "FILE_IO_WRITE";

  /** {@code MESSAGE_RECEIVE}. */
  public static final String MESSAGE_RECEIVE = "MESSAGE_RECEIVE";

  /** {@code MESSAGE_SEND}. */
  public static final String MESSAGE_SEND = "MESSAGE_SEND";

  /** {@code NETTY_CHANNEL_READ}. */
  public static final String NETTY_CHANNEL_READ = "NETTY_CHANNEL_READ";

  /** {@code NETTY_CHANNEL_WRITE}. */
  public static final String NETTY_CHANNEL_WRITE = "NETTY_CHANNEL_WRITE";

  /** {@code NIO_CHANNEL_ACCEPT}. */
  public static final String NIO_CHANNEL_ACCEPT = "NIO_CHANNEL_ACCEPT";

  /** {@code NIO_CHANNEL_CONNECT}. */
  public static final String NIO_CHANNEL_CONNECT = "NIO_CHANNEL_CONNECT";

  /** {@code NIO_CHANNEL_READ}. */
  public static final String NIO_CHANNEL_READ = "NIO_CHANNEL_READ";

  /** {@code NIO_SELECTOR_SELECT}. */
  public static final String NIO_SELECTOR_SELECT = "NIO_SELECTOR_SELECT";

  /** {@code QUEUE_OFFER}. */
  public static final String QUEUE_OFFER = "QUEUE_OFFER";

  /** {@code QUEUE_POLL}. */
  public static final String QUEUE_POLL = "QUEUE_POLL";

  /** {@code QUEUE_PUT}. */
  public static final String QUEUE_PUT = "QUEUE_PUT";

  /** {@code QUEUE_TAKE}. */
  public static final String QUEUE_TAKE = "QUEUE_TAKE";

  /** {@code R2DBC_STATEMENT_EXECUTE}. */
  public static final String R2DBC_STATEMENT_EXECUTE = "R2DBC_STATEMENT_EXECUTE";

  /** {@code REDIS_COMMAND}. */
  public static final String REDIS_COMMAND = "REDIS_COMMAND";

  /** {@code RPC_CALL}. */
  public static final String RPC_CALL = "RPC_CALL";

  /** {@code SCHEDULE_SUBMIT}. */
  public static final String SCHEDULE_SUBMIT = "SCHEDULE_SUBMIT";
}
