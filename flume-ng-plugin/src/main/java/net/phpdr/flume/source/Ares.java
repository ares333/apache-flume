package net.phpdr.flume.source;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.phpdr.flume.Util;

import org.apache.flume.ChannelException;
import org.apache.flume.Context;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.FlumeException;
import org.apache.flume.PollableSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Ares extends AbstractSource implements Configurable,
		PollableSource {
	private static final Logger logger = LoggerFactory.getLogger(Ares.class);
	private String finishDir = "_finished";
	private int maxThread = 16;
	private Map<String, HashMap<String, String>> param = Collections
			.synchronizedMap(new HashMap<String, HashMap<String, String>>());
	private int keepAlive;

	private Timer scannerTimer = new Timer();
	private LinkedBlockingQueue<HashMap<String, String>> queue = new LinkedBlockingQueue<HashMap<String, String>>(
			10000);
	private Map<String, Thread> threadList = Collections
			.synchronizedMap(new HashMap<String, Thread>());
	private boolean stop = false;
	private int sleepTimeBackoff = 1000;
	private HashMap<String, String> nodeFailed = null;

	@Override
	public void configure(Context context) {
		// 主目录,主目录|类型,文件超时,编码|子目录
		String[] lines = context.getString("param").split("\\s");
		for (String v : lines) {
			String[] nodes = v.split("\\|", 3);
			String[] node2 = nodes[1].split(",", 3);
			if (!this.param.containsKey(nodes[0])) {
				this.param.put(nodes[0], new HashMap<String, String>());
			}
			if (node2[2].isEmpty()) {
				throw new FlumeException("charset is empty");
			}
			this.param.get(nodes[0]).put("type", node2[0]);
			this.param.get(nodes[0]).put("expire", node2[1]);
			this.param.get(nodes[0]).put("charset", node2[2]);
			this.param.get(nodes[0]).put("dir", nodes[2]);
		}
		this.keepAlive = context.getInteger("keep-alive");
	}

	/**
	 * 定时检查this.path如果有新文件就读取
	 */
	@Override
	public synchronized void start() {
		super.start();
		scannerTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				if (Ares.this.threadList.size() < Ares.this.maxThread) {
					for (String k : Ares.this.param.keySet()) {
						String[] kArray = k.split(",");
						for (String k1 : kArray) {
							File dirTop = new File(k1);
							if (dirTop.exists()) {
								for (String v1 : Ares.this.param.get(k)
										.get("dir").split(",")) {
									File dirSub = new File(dirTop
											.getAbsolutePath() + "/" + v1);
									if (dirSub.isDirectory()) {
										HashMap<String, String> info = new HashMap<String, String>(
												Ares.this.param.get(k));
										File[] nodeFiles = dirSub.listFiles();
										for (File v2 : nodeFiles) {
											if (v2.isFile()
													&& !Ares.this.threadList
															.containsKey(v2
																	.getPath())) {
												FileReader reader = new FileReader(
														v2.getAbsolutePath(),
														info);
												Thread thread = new Thread(
														reader);
												thread.start();
												Ares.this.threadList.put(
														v2.getAbsolutePath(),
														thread);
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}, 0L, 60 * 1000);
	}

	/**
	 * 关闭文件句柄等善后工作
	 */
	@Override
	public synchronized void stop() {
		super.stop();
		this.scannerTimer.cancel();
		this.stop = true;
		try {
			while (this.threadList.size() > 0) {
				Thread.sleep(1000);
			}
		} catch (InterruptedException e) {
			logger.error(Util.e2s(e));
		}
	}

	/**
	 * 发送Event到Channel，Status不用BACKOFF(因为sleep时间会越来越长，不符合当前业务)
	 *
	 * @throws EventDeliveryException
	 */
	@Override
	public synchronized Status process() throws EventDeliveryException {
		Status status = Status.READY;
		HashMap<String, String> node = null;
		if (null != this.nodeFailed) {
			node = this.nodeFailed;
			this.nodeFailed = null;
		} else if (this.queue.size() > 0) {
			try {
				node = this.queue.take();
			} catch (InterruptedException e) {
				logger.error(Util.e2s(e));
			}
		}
		if (null != node) {
			HashMap<String, String> header = new HashMap<String, String>();
			header.put("charset", node.get("charset"));
			header.put("type", node.get("type"));
			header.put("dir", node.get("dir"));
			try {
				this.getChannelProcessor().processEvent(
						EventBuilder.withBody(node.get(null),
								Charset.forName(node.get("charset")), header));
			} catch (ChannelException e) {
				this.nodeFailed = node;
				throw new EventDeliveryException(e);
			}
		} else {
			try {
				Thread.sleep(this.sleepTimeBackoff);
			} catch (InterruptedException e) {
				logger.error(Util.e2s(e));
			}
		}
		return status;
	}

	/**
	 * 读文件到this.queue，如果文件完成就移走。
	 *
	 */
	class FileReader implements Runnable {
		String file;
		HashMap<String, String> info = new HashMap<String, String>();

		public FileReader(String file, HashMap<String, String> info) {
			this.file = file;
			this.info = info;
		}

		@Override
		public void run() {
			try {
				File file = new File(this.file);
				FileInputStream fileInputStream = new FileInputStream(file);
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(fileInputStream,
								this.info.get("charset")));
				long lastSize = file.length();
				long lastPosition = 0;
				int i = 0;
				String line = null;
				while (true) {
					if (reader.ready()) {
						line = reader.readLine();
						if (null != line) {
							lastPosition = fileInputStream.getChannel()
									.position();
							HashMap<String, String> node = new HashMap<String, String>();
							node.put(null, line);
							node.put("type", this.info.get("type"));
							node.put("charset", this.info.get("charset"));
							node.put("dir", file.getParentFile().getName());
							boolean resOffer;
							do {
								resOffer = Ares.this.queue.offer(node,
										Ares.this.keepAlive, TimeUnit.SECONDS);
							} while (false == resOffer);
						}
					} else {
						Thread.sleep(1000);
						if (++i == 60) {
							i = 0;
							// 文件inode变了
							if (file.length() != fileInputStream.getChannel()
									.size()) {
								fileInputStream = new FileInputStream(file);
								reader = new BufferedReader(
										new InputStreamReader(fileInputStream,
												this.info.get("charset")));
								fileInputStream.getChannel().position(
										lastPosition);
							}
							// 检测文件是否缩小了
							if (file.length() < lastSize) {
								Ares.logger
										.warn("file truncated, read from beginning");
								fileInputStream.getChannel().position(0L);
							}
							lastSize = file.length();
							Date date = new Date();
							if (null != this.info.get("expire")
									&& date.getTime() - file.lastModified() > new Long(
											this.info.get("expire")) * 1000) {
								File dir = new File(file.getParent() + "/"
										+ Ares.this.finishDir);
								if (!dir.exists()) {
									dir.mkdir();
								}
								String filePath = dir.getPath() + "/"
										+ file.getName();
								int suffix = 1;
								String newFilePath = filePath;
								while (new File(newFilePath).exists()) {
									newFilePath = filePath + "." + suffix;
									suffix++;
								}
								file.renameTo(new File(newFilePath));
								break;
							}
						}
					}
					if (Ares.this.stop) {
						break;
					}
				}
				reader.close();
			} catch (FileNotFoundException e) {
				Ares.logger.warn(Util.e2s(e));
			} catch (InterruptedException e) {
				Ares.logger.warn(Util.e2s(e));
			} catch (UnsupportedEncodingException e) {
				Ares.logger.warn(Util.e2s(e));
			} catch (IOException e) {
				Ares.logger.warn(Util.e2s(e));
			}
			Ares.this.threadList.remove(this.file);
		}
	}
}