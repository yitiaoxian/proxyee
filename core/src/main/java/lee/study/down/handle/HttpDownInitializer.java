package lee.study.down.handle;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.io.Closeable;
import java.io.IOException;
import lee.study.down.boot.AbstractHttpDownBootstrap;
import lee.study.down.constant.HttpDownStatus;
import lee.study.down.dispatch.HttpDownCallback;
import lee.study.down.model.ChunkInfo;
import lee.study.down.model.TaskInfo;
import lee.study.down.util.HttpDownUtil;
import lee.study.proxyee.proxy.ProxyHandleFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpDownInitializer extends ChannelInitializer {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpDownInitializer.class);

  private boolean isSsl;
  private AbstractHttpDownBootstrap bootstrap;
  private ChunkInfo chunkInfo;

  private long realContentSize;
  private boolean isSucc;

  public HttpDownInitializer(boolean isSsl, AbstractHttpDownBootstrap bootstrap,
      ChunkInfo chunkInfo) {
    this.isSsl = isSsl;
    this.bootstrap = bootstrap;
    this.chunkInfo = chunkInfo;
  }

  @Override
  protected void initChannel(Channel ch) throws Exception {
    if (bootstrap.getHttpDownInfo().getProxyConfig() != null) {
      ch.pipeline().addLast(ProxyHandleFactory.build(bootstrap.getHttpDownInfo().getProxyConfig()));
    }
    if (isSsl) {
      ch.pipeline().addLast(bootstrap.getClientSslContext().newHandler(ch.alloc()));
    }
    ch.pipeline()
        .addLast("httpCodec", new HttpClientCodec());
    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {

      private Closeable[] fileChannels;
      private TaskInfo taskInfo = bootstrap.getHttpDownInfo().getTaskInfo();
      private HttpDownCallback callback = bootstrap.getCallback();

      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
          if (msg instanceof HttpContent) {
            if (!isSucc) {
              return;
            }
            HttpContent httpContent = (HttpContent) msg;
            ByteBuf byteBuf = httpContent.content();
            int readableBytes = byteBuf.readableBytes();
            synchronized (chunkInfo) {
              Channel nowChannel = bootstrap.getChannel(chunkInfo);
              if (chunkInfo.getStatus() == HttpDownStatus.RUNNING
                  && nowChannel == ctx.channel()
                  && bootstrap.doFileWriter(chunkInfo, byteBuf.nioBuffer())) {
                //文件已下载大小
                chunkInfo.setDownSize(chunkInfo.getDownSize() + readableBytes);
                taskInfo.setDownSize(taskInfo.getDownSize() + readableBytes);
                if (callback != null) {
                  callback.onProgress(bootstrap.getHttpDownInfo(), chunkInfo);
                }
              } else {
                safeClose(ctx.channel());
                return;
              }
            }
            if (chunkInfo.getDownSize() == chunkInfo.getTotalSize()
                || (!taskInfo.isSupportRange() && msg instanceof LastHttpContent)) {
              LOGGER.debug("分段下载完成：channelId[" + ctx.channel().id() + "]\t" + chunkInfo);
              bootstrap.close(chunkInfo);
              //分段下载完成回调
              chunkInfo.setStatus(HttpDownStatus.DONE);
              taskInfo.refresh(chunkInfo);
              if (callback != null) {
                callback.onChunkDone(bootstrap.getHttpDownInfo(), chunkInfo);
              }
              synchronized (taskInfo) {
                if (taskInfo.getStatus() == HttpDownStatus.RUNNING
                    && taskInfo.getChunkInfoList().stream()
                    .allMatch((chunk) -> chunk.getStatus() == HttpDownStatus.DONE)) {
                  if (!taskInfo.isSupportRange()) {  //chunked编码最后更新文件大小
                    taskInfo.setTotalSize(taskInfo.getDownSize());
                    taskInfo.getChunkInfoList().get(0).setTotalSize(taskInfo.getDownSize());
                  }
                  if (taskInfo.getChunkInfoList().size() > 1) {
                    bootstrap.merge();
                  }
                  //文件下载完成回调
                  taskInfo.setStatus(HttpDownStatus.DONE);
                  LOGGER.debug("下载完成：channelId[" + ctx.channel().id() + "]\t" + chunkInfo);
                  if (callback != null) {
                    callback.onDone(bootstrap.getHttpDownInfo());
                  }
                }
              }
            } else if (realContentSize
                == chunkInfo.getDownSize() + chunkInfo.getOriStartPosition() - chunkInfo
                .getNowStartPosition() || (realContentSize - 1)
                == chunkInfo.getDownSize() + chunkInfo.getOriStartPosition() - chunkInfo
                .getNowStartPosition()) {  //百度响应做了手脚，会少一个字节
              //真实响应字节小于要下载的字节，在下载完成后要继续下载
              LOGGER.debug("继续下载：channelId[" + ctx.channel().id() + "]\t" + chunkInfo);
              bootstrap.retryChunkDown(chunkInfo, HttpDownStatus.CONNECTING_CONTINUE);
            }
          } else {
            HttpResponse httpResponse = (HttpResponse) msg;
            if ((httpResponse.status().code() + "").indexOf("20") != 0) {
              chunkInfo.setErrorCount(chunkInfo.getErrorCount() + 1);
              throw new RuntimeException("http down response error:" + httpResponse);
            }
            realContentSize = HttpDownUtil.getDownContentSize(httpResponse.headers());
            synchronized (chunkInfo) {
              //判断状态是否为连接中
              if (chunkInfo.getStatus() == HttpDownStatus.CONNECTING_NORMAL
                  || chunkInfo.getStatus() == HttpDownStatus.CONNECTING_FAIL
                  || chunkInfo.getStatus() == HttpDownStatus.CONNECTING_CONTINUE) {
                LOGGER.debug(
                    "下载响应：channelId[" + ctx.channel().id() + "]\t contentSize[" + realContentSize
                        + "]" + chunkInfo);
                chunkInfo
                    .setDownSize(chunkInfo.getNowStartPosition() - chunkInfo.getOriStartPosition());
                fileChannels = bootstrap.initFileWriter(chunkInfo);
                chunkInfo.setStatus(HttpDownStatus.RUNNING);
                if (callback != null) {
                  callback.onChunkConnected(bootstrap.getHttpDownInfo(), chunkInfo);
                }
                isSucc = true;
              } else {
                safeClose(ctx.channel());
              }
            }
          }
        } catch (Exception e) {
          throw e;
        } finally {
          ReferenceCountUtil.release(msg);
        }
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("down onChunkError:", cause);
        Channel nowChannel = bootstrap.getChannel(chunkInfo);
        safeClose(ctx.channel());
        if (nowChannel == ctx.channel()) {
          if (callback != null) {
            callback.onChunkError(bootstrap.getHttpDownInfo(), chunkInfo, cause);
          }
          bootstrap.retryChunkDown(chunkInfo);
        }
      }

      @Override
      public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        safeClose(ctx.channel());
      }

      private void safeClose(Channel channel) {
        try {
          HttpDownUtil.safeClose(channel, fileChannels);
        } catch (IOException e) {
          LOGGER.error("safeClose fail:", e);
        }
      }

    });
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    super.exceptionCaught(ctx, cause);
    LOGGER.error("down onInit:", cause);
  }
}
