package org.nosco.ant;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

import org.nosco.Query;
import org.nosco.Table;
import org.nosco.Join.J2;
import org.nosco.Join.J3;

public class JoinGenerator {

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(final String[] args) throws IOException {
		genJoinsFile(new File("Join.java"), 16);
	}

	private static void genJoinsFile(final File file, final int n) throws IOException {
		final BufferedWriter w = new BufferedWriter(new FileWriter(file));
		genJoins(w, n);
		w.close();
	}

	private static String[] ops = {"insert", "update", "delete", "save", "exists"};

	private static void genJoins(final Writer w, final int n) throws IOException {
		w.write("package org.nosco;\n\n");
		w.write("import java.sql.SQLException;\n");
		w.write("import java.util.ArrayList;\n");
		w.write("import java.util.Collections;\n");
		w.write("import java.util.List;\n");
		w.write("import javax.sql.DataSource;\n");
		w.write("import org.nosco.Field.FK;\n\n");
		w.write("public class Join {\n");
		w.write("\n");

		w.write("\tstatic abstract class J extends Table {\n");
		w.write("\n");
		w.write("\t\tList<Class<?>> types = null;\n");
		w.write("\t}\n");

		for (int i=2; i<=n; ++i) {
			w.write("\n");

			final String tExtendsTable =genTExtendsTable(i);
			final String tTypes =genTTypes(i);


			final String[] joinTypes = {"left", "right", "inner", "outer", "cross"};
			for (final String joinType : joinTypes) {
				writeJoinJavadoc(w, i, tTypes);
				if (i == 2) {
					w.write("\tpublic static <"+ tExtendsTable +"> Query"+ i +"<"+ tTypes);
					w.write("> "+ joinType +"(final Class<T1> t1, Class<T2> t2");
					if (!"cross".equals(joinType)) w.write(", Condition on");
					w.write(") {\n");
					w.write("\t\treturn new Query2<T1, T2>(new DBQuery<T1>(t1), t2, \""+ joinType +" join\", "+ ("cross".equals(joinType) ? "null" : "on") +");\n");
					w.write("\t}\n");
					writeJoinJavadoc(w, i, tTypes);
					w.write("\tpublic static <"+ tExtendsTable +"> Query"+ i +"<"+ tTypes);
					w.write("> "+ joinType +"(final Query<T"+ (i-1) +"> q, Class<T"+ i +"> t");
					if (!"cross".equals(joinType)) w.write(", Condition on");
					w.write(") {\n");
					w.write("\t\treturn new Query2<T1, T2>(q, t, \""+ joinType +" join\", "+ ("cross".equals(joinType) ? "null" : "on") +");\n");
					w.write("\t}\n");
				} else {
					w.write("\tpublic static <"+ tExtendsTable +"> Query"+ i +"<"+ tTypes);
					w.write("> "+ joinType +"(final Query<J"+ (i-1) +"<"+ genTTypes(i-1));
					w.write(">> q, Class<T"+ i +"> t");
					if (!"cross".equals(joinType)) w.write(", Condition on");
					w.write(") {\n");
					w.write("\t\treturn new Query"+ i +"<"+ tTypes +">(q, t, \""+ joinType +" join\", "+ ("cross".equals(joinType) ? "null" : "on") +");\n");
					w.write("\t}\n");
				}
			}
			w.write("\n");

			w.write("\tprivate static class Query"+ i +"<"+ tExtendsTable +"> extends DBQuery<J"+ i +"<"+ tTypes +">> {\n");
			w.write("\t\tfinal int SIZE = "+ i +";\n");
			if (i == 2) {
				w.write("\t\tpublic Query2(final Query<T1> q, final Class<T2> t, String joinType, Condition on) {\n");
				w.write("\t\t\tsuper(J"+ i +".class, q, t, joinType, on);\n");
				//w.write("\t\t\ttypes = new ArrayList;\n");
				w.write("\t\t}\n");
			} else {
				w.write("\t\tQuery"+ i +"(final Query<J"+ (i-1) +"<"+ genTTypes(i-1) +">> q, final Class<T"+ i +"> t, String joinType, Condition on) {\n");
				w.write("\t\t\tsuper(J"+ i +".class, q, t, joinType, on);\n");
				w.write("\t\t}\n");
			}
			w.write("\t}\n");


			w.write("\n");
			w.write("\t/**\n");
			w.write("\t * This class represents a join across "+ i +" tables.\n");
			w.write("\t * It contains "+ i +" typed references (t1 to t"+ i +") to the join row components.\n");
			w.write("\t * (each of them containing all the columns they contributed to the join)\n");
			w.write("\t */\n");
			w.write("\tpublic static class J"+ i +" <"+ tExtendsTable +"> extends J {\n");

			w.write("\t\tprivate List<Field<?>> __NOSCO_PRIVATE_FIELDS = null;\n");
			for (int j=1; j<=i; ++j) {
				w.write("\t\tpublic final T"+ j +" t"+ j +";\n");
			}

			w.write("\t\tpublic J"+ i +"(");
			for (int j=1; j<=i; ++j) {
				w.write("final T"+ j +" t"+ j +"");
				if (j < i) w.write(", ");
			}
			w.write(") {\n");
			for (int j=1; j<=i; ++j) {
				w.write("\t\t\tthis.t"+ j +" = t"+ j +";\n");
			}
			w.write("\t\t}\n");

			w.write("\t\t@SuppressWarnings(\"unchecked\")\n");
			w.write("\t\tJ"+ i +"(final Object[] oa, final int offset) {\n");
			for (int j=1; j<=i; ++j) {
				w.write("\t\t\tt"+ j +" = (T"+ j +") oa[offset+"+ (j-1) +"];\n");
			}
			w.write("\t\t}\n");

			w.write("\t\t@Override\n");
			w.write("\t\tprotected String SCHEMA_NAME() {\n");
			w.write("\t\t\treturn ");
			for (int j=1; j<=i; ++j) {
				w.write("(t"+ j +"==null ? null : t"+ j +".SCHEMA_NAME())");
				if (j < i) w.write("+\" + \"+");
			}
			w.write(";\n");
			w.write("\t\t}\n");

			w.write("\t\t@Override\n");
			w.write("\t\tprotected String TABLE_NAME() {\n");
			w.write("\t\t\treturn ");
			for (int j=1; j<=i; ++j) {
				w.write("(t"+ j +"==null ? null : t"+ j +".TABLE_NAME())");
				if (j < i) w.write("+\" + \"+");
			}
			w.write(";\n");
			w.write("\t\t}\n");

			w.write("\t\t@Override\n");
			w.write("\t\tpublic List<Field<?>> FIELDS() {\n");
			w.write("\t\t\tif (__NOSCO_PRIVATE_FIELDS == null) {\n");
			w.write("\t\t\t\t__NOSCO_PRIVATE_FIELDS = new ArrayList<Field<?>>();\n");
			for (int j=1; j<=i; ++j) {
				w.write("\t\t\t\t__NOSCO_PRIVATE_FIELDS.addAll(t"+ j +".FIELDS());\n");
			}
			w.write("\t\t\t\t__NOSCO_PRIVATE_FIELDS = Collections.unmodifiableList(__NOSCO_PRIVATE_FIELDS);\n");
			w.write("\t\t\t}\n");
			w.write("\t\t\treturn __NOSCO_PRIVATE_FIELDS;\n");
			w.write("\t\t}\n");

			w.write("\t\t@SuppressWarnings(\"rawtypes\")\n");
			w.write("\t\t@Override\n");
			w.write("\t\tprotected FK[] FKS() {\n");
			w.write("\t\t\tfinal FK[] ret = {};\n");
			w.write("\t\t\treturn ret;\n");
			w.write("\t\t}\n");

			w.write("\t\t@Override\n");
			w.write("\t\tpublic <S> S get(final Field<S> field) {\n");
			for (int j=1; j<=i; ++j) {
				w.write("\t\t\ttry { return t"+ j +".get(field); }\n");
				w.write("\t\t\tcatch (final IllegalArgumentException e) { /* ignore */ }\n");
			}
			w.write("\t\t\tthrow new IllegalArgumentException(\"unknown field \"+ field);\n");
			w.write("\t\t}\n");

			w.write("\t\t@Override\n");
			w.write("\t\tpublic <S> void set(final Field<S> field, final S value) {\n");
			for (int j=1; j<=i; ++j) {
				w.write("\t\t\ttry { t"+ j +".set(field, value); return; }\n");
				w.write("\t\t\tcatch (final IllegalArgumentException e) { /* ignore */ }\n");
			}
			w.write("\t\t\tthrow new IllegalArgumentException(\"unknown field \"+ field);\n");
			w.write("\t\t}\n");

			for (final String op : ops) {
				final String cmp = "exists".equals(op) ? " || " : " && ";
				w.write("\t\t@Override\n");
				w.write("\t\tpublic boolean "+ op +"() throws SQLException {\n");
				w.write("\t\t\treturn ");
				for (int j=1; j<=i; ++j) {
					w.write("(t"+ j +"!=null && t"+ j +"."+ op +"())");
					if (j < i) w.write(cmp);
				}
				w.write(";\n");
				w.write("\t\t}\n");
				w.write("\t\t@Override\n");
				w.write("\t\tpublic boolean "+ op +"(DataSource ds) throws SQLException {\n");
				w.write("\t\t\treturn ");
				for (int j=1; j<=i; ++j) {
					w.write("(t"+ j +"!=null && t"+ j +"."+ op +"(ds))");
					if (j < i) w.write(cmp);
				}
				w.write(";\n");
				w.write("\t\t}\n");
			}

			w.write("\t\t@Override\n");
			w.write("\t\tprotected Object __NOSCO_PRIVATE_mapType(final Object o) {\n");
			w.write("\t\t\treturn t1.__NOSCO_PRIVATE_mapType(o);\n");
			w.write("\t\t}\n");

			w.write("\t\t@Override\n");
			w.write("\t\tpublic String toString() {\n");
			w.write("\t\t\treturn ");
			for (int j=1; j<=i; ++j) {
				w.write("t"+ j);
				if (j < i) w.write(" +\"+\"+ ");
			}
			w.write(";\n");
			w.write("\t\t}\n");

			w.write("\t\t@Override\n");
			w.write("\t\tpublic String toStringDetailed() {\n");
			w.write("\t\t\treturn ");
			for (int j=1; j<=i; ++j) {
				w.write("(t"+ j +"==null ? t"+ j +" : t"+ j +".toStringDetailed())");
				if (j < i) w.write(" +\"+\"+ ");
			}
			w.write(";\n");
			w.write("\t\t}\n");

			w.write("\t}\n");

		}

		w.write("\n}\n");
	}

	private static void writeJoinJavadoc(final Writer w, final int i,
			final String tTypes) throws IOException {
		w.write("\t/** \n");
		w.write("\t * Joins types "+ tTypes +" into one query.\n");
		w.write("\t * The return is a private type (to avoid type erasure conflicts), but you should use\n");
		w.write("\t * it as a {@code org.nosco.Query<Join.J"+ i +"<"+ tTypes +">>}\n");
		w.write("\t */\n");
	}

	private static String genTTypes(final int i) {
		final StringBuffer sb = new StringBuffer();
		for (int j=1; j<=i; ++j) {
			sb.append("T"+ j);
			if (j < i) sb.append(", ");
		}
		return sb.toString();
	}

	public static String genTExtendsTable(final int i) {
		final StringBuffer sb = new StringBuffer();
		for (int j=1; j<=i; ++j) {
			sb.append("T"+ j +" extends Table");
			if (j < i) sb.append(", ");
		}
		return sb.toString();
	}

}
