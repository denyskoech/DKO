package org.kered.dko;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.kered.dko.Tuple.Tuple2;


/**
 * This class is used for tweaking query executions. &nbsp;
 * Multiple context levels are supported (currently: vm, thread-group and thread)
 * in increasing order of precedence. &nbsp;
 *
 * A context can start and stop transactions, set default {@code DataSource}s (optionally
 * per package or class), and change what schema is referenced.
 *
 * Each context-altering method returns an {@code Undoer} object, which lets you undo the
 * context change at a later date. (by calling {@code Undoer.undo()}) &nbsp;
 * By default all {@code Undoer}s undo themselves when they're GCed. &nbsp; But you
 * can suppress this behavior by calling {@code Undoer.setAutoUndo(false)}.
 *
 * Typical use
 * would be like the following (where {@code db.DEFAULT} is the data source generated by
 * the {@code CodeGenerator} or pulled from a {@code Query} instance):
 * <pre>   {@code Context.getThreadContext().startTransaction(db.DEFAULT);
 *   SomeClass x = SomeClass.ALL.get(SomeClass.ID.eq(123));
 *   x.setName("my new name");
 *   x.update();
 *   doSomeOtherWork();
 *   SomeClass.ALL.where(SomeClass.ID.eq(456)).deleteAll();
 *   Context.getThreadContext().commitTransaction(db.DEFAULT);}</pre>
 * Note that database calls (through this API) in {@code doSomeOtherWork()} will also be within the
 * transaction (as long as they're still within the same thread).
 *
 * @author Derek Anderson
 */
public class Context {


	/**
	 * @return the context for the current thread
	 */
	public static Context getThreadContext() {
		return threadContextContainer.get();
	}

	/**
	 * Threads spawned by default share their parent's thread group. &nbsp;
	 * Use this if you want child threads spawned by some process to share this context.
	 * @return the context for the current thread group
	 */
	public static Context getThreadGroupContext() {
		final ThreadGroup tg = Thread.currentThread().getThreadGroup();
		Context context = threadGroupContexts.get(tg);
		if (context == null) {
			context = new Context();
			threadGroupContexts.put(tg, context);
		}
		return context;
	}

	/**
	 * @return the singleton context for this VM
	 */
	public static Context getVMContext() {
		return vmContext;
	}

	static DataSource getDataSource(final Class<? extends Table> cls) {
		final Context[] contexts = {getThreadContext(), getThreadGroupContext(), getVMContext()};
		for (final Context context : contexts) {
			DataSource ds = null;

			Map<UUID, DataSource> x = context.classDataSources.get(cls);
			if (x != null) {
				synchronized(x) {
					for (final DataSource tmp : x.values()) { ds = tmp; }
				}
				if (ds != null) return ds;
			}

			//System.err.println("woot2 "+ context);
			x = context.packageDataSources.get(cls.getPackage());
			if (x != null) {
				synchronized(x) {
					for (final DataSource tmp : x.values()) { ds = tmp; }
				}
				//System.err.println("woot2.1 "+ ds);
				if (ds != null) return ds;
			}

			//System.err.println("woot3 "+ context);
			synchronized(context.defaultDataSource) {
				for (final DataSource tmp : context.defaultDataSource.values()) { ds = tmp; }
			}
			if (ds != null) return ds;
		}
		return null;
	}

	static String getSchemaToUse(final DataSource ds, final String originalSchema) {
		final Tuple2<DataSource, String> key = new Tuple2<DataSource,String>(ds, originalSchema);
		String schema = null;
		final Context[] contexts = {getThreadContext(), getThreadGroupContext(), getVMContext()};
		for (final Context context : contexts) {
			final Map<UUID, String> x = context.schemaOverrides.get(key);
			if (x == null) continue;
			synchronized(x) {
				for (final String tmp : x.values()) { schema = tmp; }
			}
			if (schema != null) return schema;
		}
		return originalSchema;
	}

	static boolean usageWarningsEnabled() {
		final Context[] contexts = {getThreadContext(), getThreadGroupContext(), getVMContext()};
		for (final Context context : contexts) {
			Boolean x = null;
			synchronized(context.enableUsageWarnings) {
				for (final Boolean v : context.enableUsageWarnings.values()) {
					x = v;
				}
			}
			if (x != null) return x;
		}
		String prop = System.getProperty(Constants.PROPERTY_WARN_EXCESSIVE_LAZY_LOADING);
		if (prop == null) prop = System.getProperty(Constants.PROPERTY_WARN_EXCESSIVE_LAZY_LOADING_OLD);
		if (prop != null) return Util.truthy(prop);
		return true;
	}

	static boolean selectOptimizationsEnabled() {
		final Context[] contexts = {getThreadContext(), getThreadGroupContext(), getVMContext()};
		for (final Context context : contexts) {
			Boolean x = null;
			synchronized(context.enableSelectOptimizations) {
				for (final Boolean v : context.enableSelectOptimizations.values()) {
					x = v;
				}
			}
			if (x != null) return x;
		}
		String prop = System.getProperty(Constants.PROPERTY_OPTIMIZE_SELECT_FIELDS);
		if (prop == null) prop = System.getProperty(Constants.PROPERTY_OPTIMIZE_SELECT_FIELDS_OLD);
		if (prop != null) return Util.truthy(prop);
		return true;
	}

	/**
	 * Returns true if currently inside a transaction.
	 * @param ds
	 * @return
	 */
	public static boolean inTransaction(final DataSource ds) {
		boolean isInTransaction = getThreadContext().transactionConnections.containsKey(ds);
		if (isInTransaction) return true;
		isInTransaction = getThreadGroupContext().transactionConnections.containsKey(ds);
		if (isInTransaction) return true;
		return getVMContext().transactionConnections.containsKey(ds);
	}

	/**
	 * Gets the connection for this transaction.
	 * @param ds
	 * @return null is not currently in a transaction
	 */
	public static Connection getConnection(final DataSource ds) {
		Connection c = getThreadContext().transactionConnections.get(ds);
		if (c == null) c = getThreadGroupContext().transactionConnections.get(ds);
		if (c == null) c = getVMContext().transactionConnections.get(ds);
		return c;
	}

	/**
	 * Starts a new transaction.
	 * @param ds
	 * @return success
	 * @throws SQLException
	 */
	public boolean startTransaction(final DataSource ds) throws SQLException {
		Connection c = transactionConnections.get(ds);
		if (c != null) return false;
		c = ds.getConnection();
		if (Constants.DB_TYPE.detect(ds)==Constants.DB_TYPE.SQLITE3) {
			Statement stmt = c.createStatement();
			try {
				String sql = "begin transaction";
				Util.log(sql, null);
				stmt.execute(sql);
			} finally {
				stmt.close();
			}
		} else {
			Util.log("connection.setAutoCommit(false)", null);
			c.setAutoCommit(false);
		}
		transactionConnections.put(ds, c);
		return true;
	}

	/**
	 * Commits the current transaction.
	 * @param ds
	 * @return
	 * @throws SQLException
	 */
	public boolean commitTransaction(final DataSource ds) throws SQLException {
		final Connection c = transactionConnections.remove(ds);
		if (c == null) return false;
		if (Constants.DB_TYPE.detect(ds)==Constants.DB_TYPE.SQLITE3) {
			Statement stmt = c.createStatement();
			try {
				String sql = "commit";
				Util.log(sql, null);
				stmt.execute(sql);
			} finally {
				stmt.close();
			}
		} else {
			Util.log("connection.commit()", null);
			c.commit();
		}
		c.close();
		return true;
	}

	/**
	 * Rolls back the current transaction. &nbsp;
	 * This method hides the {@code SQLException} thrown by the rollback.
	 * @param ds
	 * @return
	 */
	public boolean rollbackTransaction(final DataSource ds) {
		final Connection c = transactionConnections.get(ds);
		transactionConnections.remove(ds);
		if (c == null) return false;
		try {
			if (Constants.DB_TYPE.detect(ds)==Constants.DB_TYPE.SQLITE3) {
				Statement stmt = c.createStatement();
				try {
					String sql = "rollback";
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					Util.log(sql, null);
					stmt.execute(sql);
				} finally {
					stmt.close();
				}
			} else {
				Util.log("connection.rollback()", null);
				c.rollback();
			}
		} catch (final SQLException e) {
			e.printStackTrace();
			try {
				c.close();
			} catch (final SQLException e2) {
				e2.printStackTrace();
			}
			return false;
		}
		try {
			c.close();
		} catch (final SQLException e) {
			e.printStackTrace();
		}
		return true;
	}

	/**
	 * Rolls back the current transaction.
	 * @param ds
	 * @return
	 * @throws SQLException
	 */
	public boolean rollbackTransactionThrowSQLException(final DataSource ds) throws SQLException {
		final Connection c = transactionConnections.get(ds);
		transactionConnections.remove(ds);
		if (c == null) return false;
		try {
			c.rollback();
		} catch (final SQLException e) {
			throw e;
		} finally {
			c.close();
		}
		return true;
	}

	/**
	 * @deprecated Use {@link #overrideDatabaseName(DataSource,String,String)} instead
	 */
	public Undoer overrideSchema(final DataSource ds, final String originalSchema, final String newSchema) {
		return overrideDatabaseName(ds, originalSchema, newSchema);
	}

	/**
	 * All generated classes have their database name embedded in them, and always specify their
	 * schema when performing a query. &nbsp; You can change what schema they reference by
	 * overriding it here.  (per {@code DataSource})
	 * @param ds
	 * @param originalDatabaseName
	 * @param newDatabaseName
	 * @return
	 */
	public Undoer overrideDatabaseName(final DataSource ds, final String originalDatabaseName, final String newDatabaseName) {
		final Tuple2<DataSource, String> key = new Tuple2<DataSource,String>(ds, originalDatabaseName);
		Map<UUID, String> map = schemaOverrides.get(key);
		if (map == null) {
			map = Collections.synchronizedMap(new LinkedHashMap<UUID, String>());
			schemaOverrides.put(key, map);
		}
		final UUID uuid = UUID.randomUUID();
		map.put(uuid, newDatabaseName);
		final Map<UUID, String> map2 = map;
		return new Undoer() {
			@Override
			public void undo() {
				map2.remove(uuid);
			}
		};
	}

	/**
	 * Sets a default {@code DataSource} for this context. &nbsp; This will be overridden
	 * by future calls to this method, or any matching calls
	 * to the package or class versions of this method.
	 * @param ds
	 * @return
	 */
	public Undoer setDataSource(final DataSource ds) {
		final UUID uuid = UUID.randomUUID();
		defaultDataSource.put(uuid, ds);
		return new Undoer() {
			@Override
			public void undo() {
				defaultDataSource.remove(uuid);
			}
		};
	}

	/**
	 * Sets a default {@code DataSource} for all classes in this package in this context. &nbsp;
	 * This will be overridden
	 * by future calls to this method, or any matching calls
	 * to the class version of this method.
	 * @param pkg
	 * @param ds
	 * @return
	 */
	public Undoer setDataSource(final Package pkg, final DataSource ds) {
		Map<UUID, DataSource> map = packageDataSources.get(pkg);
		if (map == null) {
			map = Collections.synchronizedMap(new LinkedHashMap<UUID, DataSource>());
			packageDataSources.put(pkg, map);
		}
		final UUID uuid = UUID.randomUUID();
		map.put(uuid, ds);
		final Map<UUID, DataSource> map2 = map;
		return new Undoer() {
			@Override
			public void undo() {
				map2.remove(uuid);
			}
		};
	}

	/**
	 * Sets a default {@code DataSource} for the specified class in this context.
	 * @param cls
	 * @param ds
	 * @return
	 */
	public Undoer setDataSource(final Class<? extends Table> cls, final DataSource ds) {
		Map<UUID, DataSource> map = classDataSources.get(cls);
		if (map == null) {
			map = Collections.synchronizedMap(new LinkedHashMap<UUID, DataSource>());
			classDataSources.put(cls, map);
		}
		final UUID uuid = UUID.randomUUID();
		map.put(uuid, ds);
		final Map<UUID, DataSource> map2 = map;
		return new Undoer() {
			@Override
			public void undo() {
				map2.remove(uuid);
			}
		};
	}

	/**
	 * Turns on and off warnings for "bad" usage patterns.
	 * (like lazy loading fk relationships in a tight loop)
	 * @param enable
	 * @return
	 */
	public Undoer enableUsageWarnings(final boolean enable) {
		final UUID uuid = UUID.randomUUID();
		enableUsageWarnings.put(uuid, enable);
		return new Undoer() {
			@Override
			public void undo() {
				enableUsageWarnings.remove(uuid);
			}
		};
	}

	/**
	 * Turns on and off select optimizations that par down selected fields that are never used.
	 * @param enable
	 * @return
	 */
	public Undoer enableSelectOptimizations(final boolean enable) {
		final UUID uuid = UUID.randomUUID();
		enableSelectOptimizations.put(uuid, enable);
		return new Undoer() {
			@Override
			public void undo() {
				enableSelectOptimizations.remove(uuid);
			}
		};
	}


	/**
	 * Allows you to undo any context change. &nbsp; By default will automatically undo
	 * once this object is GCed, but this can be turned off by calling {@code setAutoUndo(false)}.
	 * @author Derek Anderson
	 */
	public static abstract class Undoer {
		private boolean autoRevoke = true;
		public abstract void undo();
		public boolean willAutoUndo() {
			return autoRevoke ;
		}
		public Undoer setAutoUndo(final boolean v) {
			autoRevoke = v;
			return this;
		}

		private Undoer() {}
		protected void finalize() {
			if (autoRevoke) undo();
		}
	}

	private static Context vmContext = new Context();

	private static Map<ThreadGroup,Context> threadGroupContexts =
			Collections.synchronizedMap(new HashMap<ThreadGroup,Context>());

	private static ThreadLocal<Context> threadContextContainer = new ThreadLocal<Context>() {
		@Override
		protected Context initialValue() {
			return new Context();
		}
	};

	private final Map<Tuple2<DataSource,String>,Map<UUID,String>> schemaOverrides =
			Collections.synchronizedMap(new HashMap<Tuple2<DataSource,String>,Map<UUID,String>>());

	private final Map<UUID,Boolean> enableUsageWarnings =
			Collections.synchronizedMap(new LinkedHashMap<UUID,Boolean>());

	private final Map<UUID,Boolean> enableSelectOptimizations =
			Collections.synchronizedMap(new LinkedHashMap<UUID,Boolean>());

	private final Map<UUID,DataSource> defaultDataSource =
			Collections.synchronizedMap(new LinkedHashMap<UUID,DataSource>());

	private final Map<Package,Map<UUID,DataSource>> packageDataSources =
			Collections.synchronizedMap(new LinkedHashMap<Package,Map<UUID,DataSource>>());

	private final Map<Class<?>,Map<UUID,DataSource>> classDataSources =
			Collections.synchronizedMap(new LinkedHashMap<Class<?>,Map<UUID,DataSource>>());

	private final Map<DataSource,Connection> transactionConnections =
			Collections.synchronizedMap(new HashMap<DataSource,Connection>());

}
