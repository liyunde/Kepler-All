package com.kepler.connection.impl;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.kepler.KeplerException;
import com.kepler.KeplerTimeoutException;
import com.kepler.ack.AckTimeOut;
import com.kepler.ack.impl.AckFuture;
import com.kepler.admin.transfer.Collector;
import com.kepler.channel.ChannelContext;
import com.kepler.channel.ChannelInvoker;
import com.kepler.config.Profile;
import com.kepler.config.PropertiesUtils;
import com.kepler.connection.Connect;
import com.kepler.connection.Connects;
import com.kepler.connection.codec.CodecHeader;
import com.kepler.connection.codec.Decoder;
import com.kepler.connection.codec.Encoder;
import com.kepler.host.Host;
import com.kepler.host.HostLocks;
import com.kepler.host.HostsContext;
import com.kepler.host.impl.SegmentLocks;
import com.kepler.protocol.Request;
import com.kepler.protocol.Response;
import com.kepler.service.Quiet;
import com.kepler.token.TokenContext;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.AttributeKey;

/**
 * Client 2 Service Connection
 * 
 * @author kim 2015年7月10日
 */
public class DefaultConnect implements Connect {

	/**
	 * 连接超时
	 */
	private static final int TIMEOUT = PropertiesUtils.get(DefaultConnect.class.getName().toLowerCase() + ".timeout", 5000);

	/**
	 * 黏包最大长度
	 */
	private static final int FRAGEMENT = PropertiesUtils.get(DefaultConnect.class.getName().toLowerCase() + ".fragement", Integer.MAX_VALUE);

	/**
	 * 发送/接受缓冲区大小
	 */
	private static final int BUFFER_SEND = PropertiesUtils.get(DefaultConnect.class.getName().toLowerCase() + ".buffer_send", Integer.MAX_VALUE);

	private static final int BUFFER_RECV = PropertiesUtils.get(DefaultConnect.class.getName().toLowerCase() + ".buffer_recv", Integer.MAX_VALUE);

	/**
	 * 监听待重连线程数量
	 */
	private static final int ESTABLISH_THREAD = PropertiesUtils.get(DefaultConnect.class.getName().toLowerCase() + ".establish_thread", 1);

	/**
	 * 是否允许本地回路
	 */
	private static final boolean ESTABLISH_LOOP = PropertiesUtils.get(DefaultConnect.class.getName().toLowerCase() + ".establish_loop", true);

	/**
	 * 是否使用共享Shared EventLoopGroup
	 */
	private static final boolean EVENTLOOP_SHARED = PropertiesUtils.get(DefaultConnect.class.getName().toLowerCase() + ".eventloop_shared", true);

	/**
	 * EventLoopGroup线程数量
	 */
	private static final int EVENTLOOP_THREAD = PropertiesUtils.get(DefaultConnect.class.getName().toLowerCase() + ".eventloop_thread", Runtime.getRuntime().availableProcessors() * 2);

	private static final ChannelFactory<SocketChannel> FACTORY = new DefaultChannelFactory<SocketChannel>(NioSocketChannel.class);

	private static final AttributeKey<Acks> ACK = AttributeKey.newInstance("ACKS");

	private static final Log LOGGER = LogFactory.getLog(DefaultConnect.class);

	/**
	 * 共享EventLoopGroup, 如果没有开启则为Null
	 */
	private final EventLoopGroup shared = DefaultConnect.EVENTLOOP_SHARED ? new NioEventLoopGroup(DefaultConnect.EVENTLOOP_THREAD) : null;

	private final InitializerFactory inits = new InitializerFactory();

	/**
	 * 建立连接任务,无状态
	 */
	private final Runnable establish = new EstablishRunnable();

	private final HostLocks locks = new SegmentLocks();

	private final Host local;

	private final Quiet quiet;

	private final Encoder encoder;

	private final Decoder decoder;

	private final Profile profiles;

	private final Connects connects;

	private final AckTimeOut timeout;

	private final TokenContext token;

	private final Collector collector;

	private final HostsContext context;

	private final ChannelContext channels;

	private final ThreadPoolExecutor threads;

	volatile private boolean shutdown;

	public DefaultConnect(Host local, Quiet quiet, Encoder encoder, Decoder decoder, Profile profiles, Connects connects, TokenContext token, AckTimeOut timeout, HostsContext context, ChannelContext channels, Collector collector, ThreadPoolExecutor threads) {
		super();
		this.local = local;
		this.token = token;
		this.quiet = quiet;
		this.encoder = encoder;
		this.decoder = decoder;
		this.threads = threads;
		this.context = context;
		this.timeout = timeout;
		this.connects = connects;
		this.channels = channels;
		this.profiles = profiles;
		this.collector = collector;
	}

	public void init() {
		// 开启重连线程
		for (int index = 0; index < DefaultConnect.ESTABLISH_THREAD; index++) {
			this.threads.execute(this.establish);
		}
		// 黏包
		this.inits.add(new LengthFieldPrepender(CodecHeader.DEFAULT));
	}

	public void destroy() throws Exception {
		this.shutdown = true;
		this.release4shared();
	}

	/**
	 * 关闭共享EventLoopGroup(如果开启)
	 * @throws Exception
	 */
	private void release4shared() throws Exception {
		if (DefaultConnect.EVENTLOOP_SHARED && !this.shared.isShutdown()) {
			this.shared.shutdownGracefully().sync();
			DefaultConnect.LOGGER.info("Shutdown shared eventloop: " + this.shared + " ... ");
		}
	}

	/**
	 * 如果非共享EventLoopGroup则关闭当前EventLoopGroup
	 * 
	 * @param boot
	 * @param host
	 * @throws Exception
	 */
	private void release4private(Bootstrap boot, Host host) throws Exception {
		// 私有EventLoop才释放
		if (!DefaultConnect.EVENTLOOP_SHARED && !boot.group().isShutdown()) {
			boot.group().shutdownGracefully().sync();
			DefaultConnect.LOGGER.warn("Shutdown private eventloop for host: " + host + " ... ");
		}
	}

	private void banAndRelease(ChannelInvoker invoker) throws Exception {
		this.context.ban(invoker.host());
		invoker.releaseAtOnce();
	}

	private void banAndRelease(Host host) throws Exception {
		// 如果多个请求(Request)同时出现故障并关闭导致再次返回Invoker为Null(与this.context.ban为强先后顺序)
		ChannelInvoker invoker = this.channels.del(host);
		if (invoker != null) {
			invoker.release();
		}
		// 加入Ban名单(Close并不意味着连接永远移除.只要ZK中未注销, 对应Host将再次尝试重连)
		this.context.ban(host);
	}

	public void connect(Host host) throws Exception {
		// 与this.channels.contain内部锁不同, 该锁用于同步建立Host的过程
		synchronized (this.locks.get(host)) {
			// 1个Host仅允许建立1个连接
			if (!this.channels.contain(host)) {
				this.connect(new InvokerHandler(new Bootstrap(), host));
			} else {
				DefaultConnect.LOGGER.warn("Host: " + host + " already connected ...");
			}
		}
		// 连接成功或已连接则激活该Host所有服务
		this.context.active(host);
	}

	/**
	 * 如果开启共享则使用共享
	 * 
	 * @return
	 */
	private EventLoopGroup eventloop() {
		return DefaultConnect.EVENTLOOP_SHARED ? this.shared : new NioEventLoopGroup(DefaultConnect.EVENTLOOP_THREAD);
	}

	private void connect(InvokerHandler invoker) throws Exception {
		try {
			// 是否为回路IP
			SocketAddress remote = new InetSocketAddress(invoker.host().loop(this.local) && DefaultConnect.ESTABLISH_LOOP ? Host.LOOP : invoker.host().host(), invoker.host().port());
			invoker.bootstrap().group(this.eventloop()).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, DefaultConnect.TIMEOUT).channelFactory(DefaultConnect.FACTORY).handler(DefaultConnect.this.inits.factory(invoker)).remoteAddress(remote).connect().sync();
			// 连接成功, 加入通道. 异常则跳过
			this.channels.put(invoker.host(), invoker);
		} catch (Throwable e) {
			DefaultConnect.LOGGER.info("Connect " + invoker.host().address() + "[sid=" + invoker.host().sid() + "] failed ...", e);
			// 关闭并尝试重连
			this.banAndRelease(invoker);
			throw e;
		}
	}

	// 非共享
	private class InvokerHandler extends ChannelInboundHandlerAdapter implements ChannelInvoker {

		private final Host target;

		private final Bootstrap bootstrap;

		private ChannelHandlerContext ctx;

		private InvokerHandler(Bootstrap bootstrap, Host target) {
			super();
			this.target = target;
			this.bootstrap = bootstrap;
		}

		public Bootstrap bootstrap() {
			return this.bootstrap;
		}

		public Host host() {
			return this.target;
		}

		public void close() {
			// 仅在通道尚处激活状态时关闭
			if (this.ctx != null && this.ctx.channel().isActive()) {
				this.ctx.close().addListener(ExceptionListener.TRACE);
			}
		}

		public void release() {
			// 禁止在EventLoop线程关闭Boostrap, 会造成死锁
			DefaultConnect.this.threads.execute(new ReleaseRunnable(this.bootstrap, this.target));
		}

		public void releaseAtOnce() {
			try {
				// 在当前线程中关闭
				DefaultConnect.this.release4private(this.bootstrap, this.target);
			} catch (Exception e) {
				DefaultConnect.LOGGER.error(e.getMessage(), e);
			}
		}

		@Override
		public boolean actived() {
			return true;
		}

		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			DefaultConnect.LOGGER.info("Connect active (" + DefaultConnect.this.local + " to " + this.target + ") ...");
			(this.ctx = ctx).channel().attr(DefaultConnect.ACK).set(new Acks());
			// 初始化赋值
			this.ctx.fireChannelActive();
		}

		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			DefaultConnect.LOGGER.info("Connect inactive (" + DefaultConnect.this.local + " to " + this.target + ") ...");
			DefaultConnect.this.banAndRelease(this.target);
			ctx.fireChannelInactive();
		}

		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
			this.exceptionPrint(cause);
			// 关闭通道, 并启动Inactive
			ctx.close().addListener(ExceptionListener.TRACE);
		}

		/**
		 * 框架异常使用Error日志
		 * 
		 * @param cause
		 */
		private void exceptionPrint(Throwable cause) {
			if (KeplerException.class.isAssignableFrom(cause.getClass())) {
				DefaultConnect.LOGGER.error(cause.getMessage(), cause);
			} else {
				DefaultConnect.LOGGER.debug(cause.getMessage(), cause);
			}
		}

		public Object invoke(Request request) throws Throwable {
			// DefaultConnect.this.token.set(request, this.target.token())增加Token
			AckFuture future = new AckFuture(DefaultConnect.this.collector, this.ctx.channel().eventLoop(), DefaultConnect.this.local, this.target, DefaultConnect.this.token.set(request, this), DefaultConnect.this.profiles, DefaultConnect.this.quiet);
			try {
				// 编码
				ByteBuf buffer = DefaultConnect.this.encoder.encode(future.request());
				// 加入ACK -> 发送消息 -> 等待ACK
				if (this.ctx.channel().eventLoop().inEventLoop()) {
					this.ctx.channel().attr(DefaultConnect.ACK).get().put(future);
					this.ctx.writeAndFlush(buffer).addListener(ExceptionListener.TRACE);
				} else {
					this.ctx.channel().eventLoop().execute(new InvokeRunnable(this.ctx, future, buffer));
				}
				// 如果为Future或@Async则立即返回, 负责线程等待
				return future.request().async() ? future : future.get();
			} catch (Throwable exception) {
				// 任何异常均释放ACK
				if (this.ctx.channel().eventLoop().inEventLoop()) {
					this.ctx.channel().attr(DefaultConnect.ACK).get().remove(request.ack());
				} else {
					this.ctx.channel().eventLoop().execute(new RemovedRunnable(this.ctx, request));
				}
				// Timeout处理
				this.timeout(future, exception);
				throw exception;
			}
		}

		/**
		 * Timeout处理, DefaultConnect.this.collector.peek(ack).timeout()当前周期Timeout次数
		 * 
		 * @param request
		 * @param ack
		 * @param exception
		 */
		private void timeout(AckFuture ack, Throwable exception) {
			// 仅处理KeplerTimeoutException
			if (KeplerTimeoutException.class.isAssignableFrom(exception.getClass())) {
				DefaultConnect.this.timeout.timeout(this, ack, DefaultConnect.this.collector.peek(ack).timeout());
			}
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
			Response response = Response.class.cast(DefaultConnect.this.decoder.decode(ByteBuf.class.cast(message)));
			// 移除ACK
			AckFuture future = this.ctx.channel().attr(DefaultConnect.ACK).get().remove(response.ack());
			// 如获取不到ACK表示已超时
			if (future != null) {
				future.response(response);
			} else {
				DefaultConnect.LOGGER.warn("Missing ack for response: " + response.ack() + " (" + this.target + "), may be timeout ...");
			}
		}
	}

	private class InitializerFactory {

		private final List<ChannelHandler> handlers = new ArrayList<ChannelHandler>();

		public void add(ChannelHandler handler) {
			this.handlers.add(handler);
		}

		public ChannelInitializer<SocketChannel> factory(final InvokerHandler handler) {
			return new ChannelInitializer<SocketChannel>() {
				protected void initChannel(SocketChannel channel) throws Exception {
					// 指定读写缓存
					channel.config().setReceiveBufferSize(DefaultConnect.BUFFER_RECV);
					channel.config().setSendBufferSize(DefaultConnect.BUFFER_SEND);
					channel.config().setAllocator(PooledByteBufAllocator.DEFAULT);
					channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(DefaultConnect.FRAGEMENT, 0, CodecHeader.DEFAULT, 0, CodecHeader.DEFAULT));
					for (ChannelHandler each : InitializerFactory.this.handlers) {
						channel.pipeline().addLast(each);
					}
					channel.pipeline().addLast(handler);
				}
			};
		}
	}

	private class ReleaseRunnable implements Runnable {

		private final Bootstrap bootstrap;

		private final Host host;

		private ReleaseRunnable(Bootstrap bootstrap, Host host) {
			super();
			this.bootstrap = bootstrap;
			this.host = host;
		}

		@Override
		public void run() {
			try {
				DefaultConnect.this.release4private(this.bootstrap, this.host);
			} catch (Exception e) {
				DefaultConnect.LOGGER.error(e.getMessage(), e);
			}
		}
	}

	/**
	 * 重连线程
	 * 
	 * @author kim 2015年7月20日
	 */
	private class EstablishRunnable implements Runnable {
		@Override
		public void run() {
			while (!DefaultConnect.this.shutdown) {
				try {
					Host host = DefaultConnect.this.connects.get();
					if (host != null) {
						DefaultConnect.this.connect(host);
					}
				} catch (Throwable e) {
					DefaultConnect.LOGGER.debug(e.getMessage(), e);
				}
			}
			DefaultConnect.LOGGER.warn(this.getClass() + " shutdown on thread (" + Thread.currentThread().getId() + ")");
		}
	}

	private class InvokeRunnable implements Runnable {

		private final ChannelHandlerContext ctx;

		private final AckFuture future;

		private final ByteBuf buffer;

		private InvokeRunnable(ChannelHandlerContext ctx, AckFuture future, ByteBuf buffer) {
			super();
			this.future = future;
			this.buffer = buffer;
			this.ctx = ctx;
		}

		@Override
		public void run() {
			this.ctx.channel().attr(DefaultConnect.ACK).get().put(this.future);
			this.ctx.writeAndFlush(this.buffer).addListener(ExceptionListener.TRACE);
		}
	}

	private class RemovedRunnable implements Runnable {

		private final ChannelHandlerContext ctx;

		private final Request request;

		public RemovedRunnable(ChannelHandlerContext ctx, Request request) {
			super();
			this.ctx = ctx;
			this.request = request;
		}

		@Override
		public void run() {
			this.ctx.channel().attr(DefaultConnect.ACK).get().remove(this.request.ack());
		}
	}

	private class Acks {

		private final Map<Bytes, AckFuture> waitings = new HashMap<Bytes, AckFuture>();

		public AckFuture put(AckFuture future) {
			this.waitings.put(new Bytes(future.request().ack()), future);
			return future;
		}

		public AckFuture remove(byte[] ack) {
			return this.waitings.remove(new Bytes(ack));
		}
	}

	private class Bytes {

		private final byte[] bytes;

		private Bytes(byte[] bytes) {
			this.bytes = bytes;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			Bytes that = Bytes.class.cast(obj);
			return Arrays.equals(this.bytes, that.bytes);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(this.bytes);
		}
	}
}
