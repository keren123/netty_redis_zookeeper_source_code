package com.crazymakercircle.netty.http.fileserver.trunkedstream;

import com.crazymakercircle.http.HttpProtocolHelper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedInput;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.CompletableFuture;

import static com.crazymakercircle.http.HttpProtocolHelper.sendErrorOrDirectory;
import static com.crazymakercircle.util.IOUtil.closeQuietly;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Slf4j
public class HttpChunkedStreamServerHandler extends SimpleChannelInboundHandler<FullHttpRequest>
{

    private final CompletableFuture<Boolean> transferFuture = new CompletableFuture<>();

    private FullHttpRequest request;

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception
    {
        this.request = request;

        HttpProtocolHelper.cacheHttpProtocol(ctx, request);
        //??????????????????????????????????????????????????????Wie?????????
        HttpProtocolHelper.setKeepAlive(ctx, false);


        if (!request.decoderResult().isSuccess())
        {
            HttpProtocolHelper.sendError(ctx, BAD_REQUEST);
            return;
        }

        if (!GET.equals(request.method()))
        {
            HttpProtocolHelper.sendError(ctx, METHOD_NOT_ALLOWED);
            return;
        }

        // ????????????????????????????????????????????????????????????
        File file = sendErrorOrDirectory(ctx, request);
        if (file == null) return;
        /**
         * ????????????
         */
        String fname = file.getName();
        //????????????????????????
        RandomAccessFile raf = HttpProtocolHelper.openFile(ctx, file);

        //????????????
        long fileLength = raf.length();

        //???????????????????????????
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        HttpProtocolHelper.setContentTypeHeader(response, file);
        HttpUtil.setContentLength(response, fileLength);
        HttpProtocolHelper.setDateAndCacheHeaders(response, file);
        HttpProtocolHelper.setKeepAlive(ctx, response);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);


        //???????????????????????????
        ctx.write(response);

        //????????????????????????
        ChannelProgressivePromise progressivePromise = ctx.newProgressivePromise();

        //???????????????ChannelProgressiveFutureListener ???????????????
        progressivePromise.addListener(new ChannelProgressiveFutureListener()
        {
            @Override
            public void operationProgressed(ChannelProgressiveFuture future, long progress, long total)
            {
                if (total < 0)
                {
                    //????????????
                    log.info("?????????{}????????????{}", fname, progress);
                } else
                {
                    log.info("?????????{}????????????{}", fname, progress + " / " + total);
                }
            }

            @Override
            public void operationComplete(ChannelProgressiveFuture future)
            {

                if (!future.isSuccess())
                {
                    Throwable cause = future.cause();// ????????????
                    // Do something
                    cause.printStackTrace();
                    log.info("?????????????????????{}", fname);
                    transferFuture.completeExceptionally(cause);
                } else
                {
                    transferFuture.complete(true);
                }
                closeQuietly(raf);


            }
        });
        transferFuture.whenComplete((finished, cause) ->
        {
            if (finished)
            {
                log.info("?????????????????????{}", fname);
                ChannelFuture lastContentFuture =
                        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

                //????????????????????????
                lastContentFuture.addListener(ChannelFutureListener.CLOSE);
            } else
            {
                log.info("?????????????????????{}", ctx.channel());
                ctx.channel().close();

            }

        });
        // ????????????
        ChunkedInput<ByteBuf> chunkedFile = new ChunkedFile(raf, 0, fileLength, 8192);
        ctx.writeAndFlush(
                new HttpChunkedInput(chunkedFile), progressivePromise);

    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
    {
        cause.printStackTrace();
        if (ctx.channel().isActive())
        {
            HttpProtocolHelper.sendError(ctx, INTERNAL_SERVER_ERROR);
        }
    }

}
