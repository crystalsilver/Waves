package com.wavesplatform.network

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import com.wavesplatform.features.FeatureProvider
import com.wavesplatform.metrics.Metrics
import com.wavesplatform.mining.Miner
import com.wavesplatform.settings._
import com.wavesplatform.state2._
import com.wavesplatform.{UtxPool, Version}
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.channel._
import io.netty.channel.group.ChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import io.netty.util.concurrent.DefaultThreadFactory
import org.influxdb.dto.Point
import scorex.transaction._
import scorex.utils.{ScorexLogging, Time}

import scala.concurrent.duration._

class NetworkServer(checkpointService: CheckpointService,
                    blockchainUpdater: BlockchainUpdater,
                    time: Time,
                    miner: Miner,
                    stateReader: StateReader,
                    settings: WavesSettings,
                    history: NgHistory,
                    utxPool: UtxPool,
                    peerDatabase: PeerDatabase,
                    allChannels: ChannelGroup,
                    peerInfo: ConcurrentHashMap[Channel, PeerInfo],
                    blockchainReadiness: AtomicBoolean,
                    featureProvider: FeatureProvider) extends ScorexLogging {

  @volatile
  private var shutdownInitiated = false

  private val bossGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("nio-boss-group", true))
  private val workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("nio-worker-group", true))
  private val handshake =
    Handshake(Constants.ApplicationName + settings.blockchainSettings.addressSchemeCharacter, Version.VersionTuple,
      settings.networkSettings.nodeName, settings.networkSettings.nonce, settings.networkSettings.declaredAddress)

  private val scoreObserver = new RemoteScoreObserver(
    settings.synchronizationSettings.scoreTTL,
    history.lastBlockIds(settings.synchronizationSettings.maxRollback), history.score())

  private val discardingHandler = new DiscardingHandler(blockchainReadiness)
  private val messageCodec = new MessageCodec(peerDatabase)

  private val lengthFieldPrepender = new LengthFieldPrepender(4)

  // There are two error handlers by design. WriteErrorHandler adds a future listener to make sure writes to network
  // succeed. It is added to the head of pipeline (it's the closest of the two to actual network), because some writes
  // are initiated from the middle of the pipeline (e.g. extension requests). FatalErrorHandler, on the other hand,
  // reacts to inbound exceptions (the ones thrown during channelRead). It is added to the tail of pipeline to handle
  // exceptions bubbling up from all the handlers below. When a fatal exception is caught (like OutOfMemory), the
  // application is terminated.
  private val writeErrorHandler = new WriteErrorHandler
  private val fatalErrorHandler = new FatalErrorHandler
  private val historyReplier = new HistoryReplier(history, settings.synchronizationSettings)
  private val inboundConnectionFilter: PipelineInitializer.HandlerWrapper = new InboundConnectionFilter(peerDatabase,
    settings.networkSettings.maxInboundConnections,
    settings.networkSettings.maxConnectionsPerHost)

  private val microBlockOwners = new MicroBlockOwners(settings.synchronizationSettings.microBlockSynchronizer.invCacheTimeout)
  private val coordinatorHandler = new CoordinatorHandler(checkpointService, history, blockchainUpdater, time,
    stateReader, utxPool, blockchainReadiness, miner, settings, peerDatabase, allChannels, featureProvider, microBlockOwners)

  private val peerConnections = new ConcurrentHashMap[PeerKey, Channel](10, 0.9f, 10)

  private val serverHandshakeHandler =
    new HandshakeHandler.Server(handshake, peerInfo, peerConnections, peerDatabase, allChannels)

  private val utxPoolSynchronizer = new UtxPoolSynchronizer(utxPool, allChannels)
  private val microBlockSynchronizer = new MicroBlockSynchronizer(
    settings.synchronizationSettings.microBlockSynchronizer,
    history,
    peerDatabase,
    blockchainUpdater.lastBlockId,
    microBlockOwners
  )


  private val serverChannel = settings.networkSettings.declaredAddress.map { _ =>
    new ServerBootstrap()
      .group(bossGroup, workerGroup)
      .channel(classOf[NioServerSocketChannel])
      .childHandler(new PipelineInitializer[SocketChannel](Seq(
        inboundConnectionFilter,
        writeErrorHandler,
        new HandshakeDecoder(peerDatabase),
        new HandshakeTimeoutHandler(settings.networkSettings.handshakeTimeout),
        serverHandshakeHandler,
        lengthFieldPrepender,
        new LengthFieldBasedFrameDecoder(100 * 1024 * 1024, 0, 4, 0, 4),
        new LegacyFrameCodec(peerDatabase),
        discardingHandler,
        messageCodec,
        peerSynchronizer,
        historyReplier,
        microBlockSynchronizer,
        new ExtensionSignaturesLoader(settings.synchronizationSettings.synchronizationTimeout, peerDatabase),
        new ExtensionBlocksLoader(settings.synchronizationSettings.synchronizationTimeout, peerDatabase, history),
        new OptimisticExtensionLoader,
        utxPoolSynchronizer,
        scoreObserver,
        coordinatorHandler,
        fatalErrorHandler)))
      .bind(settings.networkSettings.bindAddress)
      .channel()
  }

  private val outgoingChannels = new ConcurrentHashMap[InetSocketAddress, Channel]

  private val clientHandshakeHandler =
    new HandshakeHandler.Client(handshake, peerInfo, peerConnections, peerDatabase, allChannels)

  private val bootstrap = new Bootstrap()
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.networkSettings.connectionTimeout.toMillis.toInt: Integer)
    .group(workerGroup)
    .channel(classOf[NioSocketChannel])
    .handler(new PipelineInitializer[SocketChannel](Seq(
      writeErrorHandler,
      new HandshakeDecoder(peerDatabase),
      new HandshakeTimeoutHandler(settings.networkSettings.handshakeTimeout),
      clientHandshakeHandler,
      lengthFieldPrepender,
      new LengthFieldBasedFrameDecoder(100 * 1024 * 1024, 0, 4, 0, 4),
      new LegacyFrameCodec(peerDatabase),
      discardingHandler,
      messageCodec,
      peerSynchronizer,
      historyReplier,
      microBlockSynchronizer,
      new ExtensionSignaturesLoader(settings.synchronizationSettings.synchronizationTimeout, peerDatabase),
      new ExtensionBlocksLoader(settings.synchronizationSettings.synchronizationTimeout, peerDatabase, history),
      new OptimisticExtensionLoader,
      utxPoolSynchronizer,
      scoreObserver,
      coordinatorHandler,
      fatalErrorHandler)))

  private val connectTask = workerGroup.scheduleWithFixedDelay(1.second, 5.seconds) {
    import scala.collection.JavaConverters._

    val outgoing = outgoingChannels.keySet.iterator().asScala.toVector
    val outgoingStr = outgoing.map(_.toString).sorted.mkString(", ")

    val incoming = peerInfo.values().iterator().asScala.flatMap(_.declaredAddress).toVector
    val incomingStr = incoming.map(_.toString).sorted.mkString(", ")

    log.trace(s"Outgoing: $outgoingStr ++ incoming: $incomingStr")
    val shouldConnect = outgoingChannels.size() < settings.networkSettings.maxOutboundConnections
    if (shouldConnect) {
      peerDatabase
        .randomPeer(outgoing.toSet ++ incoming)
        .foreach(connect)
    }

    Metrics.write(
      Point
        .measurement("connections")
        .addField("outgoing", outgoingStr)
        .addField("incoming", incomingStr)
        .addField("n", outgoingChannels.keySet.size() + incoming.size)
        .addField("connecting", shouldConnect)
    )
  }

  private def peerSynchronizer: ChannelHandlerAdapter = {
    if (settings.networkSettings.enablePeersExchange) {
      new PeerSynchronizer(peerDatabase, settings.networkSettings.peersBroadcastInterval)
    } else PeerSynchronizer.Disabled
  }

  def connect(remoteAddress: InetSocketAddress): Unit = {
    outgoingChannels.computeIfAbsent(remoteAddress, _ => {
      log.debug(s"Connecting to $remoteAddress")
      bootstrap.connect(remoteAddress)
        .addListener { (connFuture: ChannelFuture) =>
          if (connFuture.isDone) {
            if (connFuture.cause() != null) {
              val reason = s"${id(connFuture.channel())} Connection failed, suspending $remoteAddress"
              log.debug(reason, connFuture.cause())
              peerDatabase.suspend(remoteAddress.getAddress)
              outgoingChannels.remove(remoteAddress, connFuture.channel())
            } else if (connFuture.isSuccess) {
              log.trace(s"${id(connFuture.channel())} Connection established")
              peerDatabase.touch(remoteAddress)
              connFuture.channel().closeFuture().addListener { (closeFuture: ChannelFuture) =>
                val remainingCount = outgoingChannels.size()
                val reason = s"${id(closeFuture.channel)} Connection closed, $remainingCount outgoing channel(s) remaining"
                log.info(reason)
                outgoingChannels.remove(remoteAddress, closeFuture.channel())
                if (!shutdownInitiated) peerDatabase.suspend(remoteAddress.getAddress)
              }
            }
          }
        }.channel()
    })
  }

  def shutdown(): Unit = try {
    shutdownInitiated = true
    connectTask.cancel(false)
    serverChannel.foreach(_.close().await())
    log.debug("Unbound server")
    allChannels.close().await()
    log.debug("Closed all channels")
  } finally {
    workerGroup.shutdownGracefully().await()
    bossGroup.shutdownGracefully().await()
  }
}
