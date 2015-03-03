package net.phpdr.flume.sink.hbase;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.flume.conf.ComponentConfiguration;
import org.apache.flume.sink.hbase.AsyncHbaseEventSerializer;
import org.hbase.async.AtomicIncrementRequest;
import org.hbase.async.PutRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

public class AresSerializer implements AsyncHbaseEventSerializer {
	static long c = 0;
	private String tableStr;
	private HashMap<String, HashMap<String, String[]>> tables = new HashMap<String, HashMap<String, String[]>>();
	private byte[] cf;

	private HashSet<byte[]> table = new HashSet<byte[]>();
	private String charset;
	private HashMap<byte[], byte[]> rowkey = new HashMap<byte[], byte[]>();
	private HashMap<byte[], byte[][]> qualifiers = new HashMap<byte[], byte[][]>();
	private HashMap<byte[], byte[][]> values = new HashMap<byte[], byte[][]>();
	private static final Logger logger = LoggerFactory
			.getLogger(AresSerializer.class);

	/**
	 * type,dir,table|qualifiers|rowkey
	 */
	@Override
	public synchronized void initialize(byte[] table, byte[] cf) {
		this.tables.clear();
		for (String v : this.tableStr.split("\\s")) {
			String nodes[] = v.split("\\|", 3);
			String node1[] = nodes[0].split(",", 3);
			String key = node1[0] + "/" + node1[1];
			if (!this.tables.containsKey(key)) {
				this.tables.put(key, new HashMap<String, String[]>());
			}
			// node1[2]:表名
			this.tables.get(key).put(node1[2],
					new String[] { nodes[1], nodes[2] });
		}
		this.cf = cf;
	}

	/**
	 * 转换Event
	 */
	@Override
	public void setEvent(Event event) {
		try {
			c++;
			System.out.println(c + "\r");
			this.table.clear();
			this.rowkey.clear();
			this.qualifiers.clear();
			this.values.clear();
			Map<String, String> header = event.getHeaders();
			String cols[] = new String(event.getBody(), header.get("charset"))
					.split("\t");
			String key = header.get("type") + "/" + header.get("dir");
			this.charset = header.get("charset");
			if (!this.tables.containsKey(key)) {
				logger.error("table not found, type=" + header.get("type")
						+ ", dir=" + header.get("dir"));
			} else {
				HashMap<String, String[]> node = this.tables.get(key);
				// k就是表名
				for (String k : node.keySet()) {
					List<String> qualifiersList = Arrays.asList(node.get(k)[0]
							.split(","));
					String[] rowkeyArray = node.get(k)[1].split(",");
					// 计算rowkey
					String rowkey = "";
					for (String v : rowkeyArray) {
						int pos = qualifiersList.indexOf(v);
						if (-1 == pos) {
							throw new FlumeException("rowkey not found in "
									+ this.getClass().getName() + ", rowkey="
									+ v);
						} else if (pos < cols.length) {
							rowkey += cols[pos] + "_";
						} else {
							return;
						}
					}
					byte[] table = k.getBytes(this.charset);
					this.table.add(table);
					this.rowkey.put(
							table,
							rowkey.substring(0, rowkey.length() - 1).getBytes(
									this.charset));
					// 计算qualifiers和values
					int length = cols.length;
					if (length > qualifiersList.size()) {
						length = qualifiersList.size();
					}
					byte[][] qualifiers = new byte[length][];
					byte[][] values = new byte[length][];
					for (int i = 0; i < length; i++) {
						qualifiers[i] = qualifiersList.get(i).getBytes(
								this.charset);
						values[i] = cols[i].getBytes(this.charset);
					}
					this.qualifiers.put(table, qualifiers);
					this.values.put(table, values);
				}
			}
		} catch (UnsupportedEncodingException e) {
			logger.error(e.getMessage());
		}
	}

	@Override
	public List<PutRequest> getActions() {
		ArrayList<PutRequest> actions = new ArrayList<PutRequest>();
		for (byte[] v : this.table) {
			PutRequest req = new PutRequest(v, this.rowkey.get(v), this.cf,
					this.qualifiers.get(v), this.values.get(v));
			try {
				String row = new String(v, this.charset) + "\t"
						+ new String(this.rowkey.get(v), this.charset) + "\t";
				for (byte[] v1 : this.qualifiers.get(v)) {
					row += new String(v1, this.charset) + ",";
				}
				row = row.substring(0, row.length() - 1) + "\t";
				for (byte[] v1 : this.values.get(v)) {
					row += new String(v1, this.charset) + ",";
				}
				row = row.substring(0, row.length() - 1);
				logger.debug(row);
			} catch (UnsupportedEncodingException e) {
				logger.error(e.getMessage());
			}
			actions.add(req);
		}
		return actions;
	}

	@Override
	public List<AtomicIncrementRequest> getIncrements() {
		List<AtomicIncrementRequest> actions = new ArrayList<AtomicIncrementRequest>();
		for (byte[] v : this.table) {
			AtomicIncrementRequest inc = new AtomicIncrementRequest(v,
					"inc".getBytes(Charsets.UTF_8), this.cf,
					"inc".getBytes(Charsets.UTF_8));
			actions.add(inc);
		}
		return actions;
	}

	@Override
	public void cleanUp() {
	}

	@Override
	public synchronized void configure(Context context) {
		this.tableStr = context.getString("tables");
	}

	@Override
	public void configure(ComponentConfiguration conf) {
	}
}