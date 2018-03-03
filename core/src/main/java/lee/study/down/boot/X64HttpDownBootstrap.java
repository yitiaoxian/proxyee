package lee.study.down.boot;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import lee.study.down.dispatch.HttpDownCallback;
import lee.study.down.io.LargeMappedByteBuffer;
import lee.study.down.model.ChunkInfo;
import lee.study.down.model.HttpDownInfo;
import lee.study.down.model.TaskInfo;
import lee.study.down.util.FileUtil;

public class X64HttpDownBootstrap extends AbstractHttpDownBootstrap {

  public X64HttpDownBootstrap(HttpDownInfo httpDownInfo,
      int retryCount,
      SslContext clientSslContext,
      NioEventLoopGroup clientLoopGroup,
      HttpDownCallback callback,
      TimeoutCheckTask timeoutCheckTask) {
    super(httpDownInfo, retryCount, clientSslContext, clientLoopGroup, callback, timeoutCheckTask);
  }

  @Override
  public boolean continueDownHandle() throws Exception {
    TaskInfo taskInfo = getHttpDownInfo().getTaskInfo();
    if (!FileUtil.exists(taskInfo.buildTaskFilePath())) {
      close();
      startDown();
      return false;
    }
    return true;
  }

  @Override
  public void merge() throws Exception {

  }

  @Override
  public void initBoot() throws IOException {
    TaskInfo taskInfo = getHttpDownInfo().getTaskInfo();
    try (
        RandomAccessFile randomAccessFile = new RandomAccessFile(taskInfo.buildTaskFilePath(), "rw")
    ) {
      randomAccessFile.setLength(taskInfo.getTotalSize());
    }
  }

  @Override
  public Closeable[] initFileWriter(ChunkInfo chunkInfo) throws Exception {
    Closeable[] fileChannels;
    FileChannel fileChannel = new RandomAccessFile(
        getHttpDownInfo().getTaskInfo().buildTaskFilePath(), "rw")
        .getChannel();
    if (getHttpDownInfo().getTaskInfo().getConnections() > 1) {
      LargeMappedByteBuffer mappedBuffer = new LargeMappedByteBuffer(fileChannel,
          MapMode.READ_WRITE, chunkInfo.getNowStartPosition(),
          chunkInfo.getEndPosition() - chunkInfo.getNowStartPosition() + 1);
      fileChannels = new Closeable[]{fileChannel, mappedBuffer};
    } else {
      fileChannels = new Closeable[]{fileChannel};
    }
    setAttr(chunkInfo, ATTR_FILE_CHANNELS, fileChannels);
    return fileChannels;
  }

  @Override
  public boolean doFileWriter(ChunkInfo chunkInfo, ByteBuffer buffer) throws IOException {
    Closeable[] fileChannels = getFileWriter(chunkInfo);
    if (fileChannels != null) {
      if (fileChannels.length > 1) {
        LargeMappedByteBuffer mappedBuffer = (LargeMappedByteBuffer) getFileWriter(chunkInfo)[1];
        if (mappedBuffer != null) {
          mappedBuffer.put(buffer);
          return true;
        }
      } else {
        FileChannel fileChannel = (FileChannel) getFileWriter(chunkInfo)[0];
        if (fileChannel != null) {
          fileChannel.write(buffer);
          return true;
        }
      }
    }
    return false;
  }
}
