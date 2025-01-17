package console.precompiled.model;

import console.exception.ConsoleMessageException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.ItemsList;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.fisco.bcos.sdk.v3.contract.precompiled.crud.common.Condition;
import org.fisco.bcos.sdk.v3.contract.precompiled.crud.common.ConditionOperator;
import org.fisco.bcos.sdk.v3.contract.precompiled.crud.common.Entry;
import org.fisco.bcos.sdk.v3.model.PrecompiledConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CRUDParseUtils {
    private static final Logger logger = LoggerFactory.getLogger(CRUDParseUtils.class);
    public static final String PRIMARY_KEY = "primary key";

    public static void parseCreateTable(String sql, Table table)
            throws JSQLParserException, ConsoleMessageException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        CreateTable createTable = (CreateTable) statement;

        // parse table name
        String tableName = createTable.getTable().getName();
        table.setTableName(tableName);

        // parse key from index
        boolean keyFlag = false;
        List<Index> indexes = createTable.getIndexes();
        if (indexes != null) {
            if (indexes.size() > 1) {
                throw new ConsoleMessageException(
                        "Please provide only one primary key for the table.");
            }
            keyFlag = true;
            Index index = indexes.get(0);
            String type = index.getType().toLowerCase();
            if (PRIMARY_KEY.equals(type)) {
                table.setKey(index.getColumnsNames().get(0));
                table.setKeyFieldName(index.getColumnsNames().get(0));
            } else {
                throw new ConsoleMessageException(
                        "Please provide only one primary key for the table.");
            }
        }
        List<ColumnDefinition> columnDefinitions = createTable.getColumnDefinitions();
        // parse key from ColumnDefinition
        for (int i = 0; i < columnDefinitions.size(); i++) {
            List<String> columnSpecStrings = columnDefinitions.get(i).getColumnSpecStrings();
            if (columnSpecStrings == null) {
                continue;
            } else {
                if (columnSpecStrings.size() == 2
                        && "primary".equals(columnSpecStrings.get(0))
                        && "key".equals(columnSpecStrings.get(1))) {
                    String key = columnDefinitions.get(i).getColumnName();
                    if (keyFlag) {
                        if (!table.getKey().equals(key)) {
                            throw new ConsoleMessageException(
                                    "Please provide only one primary key for the table.");
                        }
                    } else {
                        keyFlag = true;
                        table.setKey(key);
                    }
                    break;
                }
            }
        }
        if (!keyFlag) {
            throw new ConsoleMessageException("Please provide a primary key for the table.");
        }
        // parse value field
        List<String> fieldsList = new ArrayList<>();
        for (int i = 0; i < columnDefinitions.size(); i++) {
            String columnName = columnDefinitions.get(i).getColumnName();
            if (fieldsList.contains(columnName)) {
                throw new ConsoleMessageException(
                        "Please provide the field '" + columnName + "' only once.");
            } else {
                fieldsList.add(columnName);
            }
        }
        if (!fieldsList.contains(table.getKey())) {
            throw new ConsoleMessageException(
                    "Please provide the field '" + table.getKey() + "' in column definition.");
        } else {
            fieldsList.remove(table.getKey());
        }
        table.setValueFields(fieldsList);
    }

    public static String parseInsertedTableName(String sql)
            throws JSQLParserException, ConsoleMessageException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        Insert insert = (Insert) statement;

        if (insert.getSelect() != null) {
            throw new ConsoleMessageException("The insert select clause is not supported.");
        }
        // parse table name
        return insert.getTable().getName();
    }

    public static boolean parseInsert(
            String sql, Table table, Entry entry, Map<String, String> tableDesc)
            throws JSQLParserException, ConsoleMessageException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        Insert insert = (Insert) statement;
        String valueFieldString = tableDesc.get(PrecompiledConstant.VALUE_FIELD_NAME);
        String[] valueFields = valueFieldString.split(",");
        String expectedValueField =
                tableDesc.get(PrecompiledConstant.KEY_FIELD_NAME) + ", " + valueFieldString;
        int expectedValueNum = valueFields.length + 1;

        if (insert.getSelect() != null) {
            throw new ConsoleMessageException("The insert select clause is not supported.");
        }
        // parse table name
        String tableName = insert.getTable().getName();
        table.setTableName(tableName);

        // parse columns
        List<Column> columns = insert.getColumns();
        ItemsList itemsList = insert.getItemsList();

        ExpressionList expressionList = (ExpressionList) itemsList;
        List<Expression> expressions = expressionList.getExpressions();

        String[] itemArr = new String[expressions.size()];
        for (int i = 0; i < expressions.size(); i++) {
            itemArr[i] = expressions.get(i).toString().trim();
        }
        if (columns != null) {
            if (columns.size() != itemArr.length) {
                throw new ConsoleMessageException("Column count doesn't match value count.");
            }
            if (expectedValueNum != columns.size()) {
                throw new ConsoleMessageException(
                        "Column count doesn't match value count, fields size: "
                                + valueFields.length
                                + ", provided field value size: "
                                + columns.size()
                                + ", expected field list: "
                                + expectedValueField);
            }
            List<String> columnNames = new ArrayList<>();
            for (Column column : columns) {
                String columnName = trimQuotes(column.toString());
                if (columnNames.contains(columnName)) {
                    throw new ConsoleMessageException(
                            "Please provide the field '" + columnName + "' only once.");
                } else {
                    columnNames.add(columnName);
                }
            }
            for (int i = 0; i < columnNames.size(); i++) {
                entry.getFieldNameToValue().put(columnNames.get(i), trimQuotes(itemArr[i]));
            }
            return false;
        } else {
            String keyField = tableDesc.get(PrecompiledConstant.KEY_FIELD_NAME);
            if (expectedValueNum != itemArr.length) {
                throw new ConsoleMessageException(
                        "Column count doesn't match value count, fields size: "
                                + valueFields.length
                                + ", provided field value size: "
                                + itemArr.length
                                + ", expected field list: "
                                + expectedValueField);
            }
            String[] allFields = new String[itemArr.length];
            allFields[0] = keyField;
            System.arraycopy(valueFields, 0, allFields, 1, valueFields.length);
            for (int i = 0; i < itemArr.length; i++) {
                entry.getFieldNameToValue().put(allFields[i], trimQuotes(itemArr[i]));
            }
            return true;
        }
    }

    public static void parseSelect(
            String sql, Table table, Condition condition, List<String> selectColumns)
            throws JSQLParserException, ConsoleMessageException {
        Statement statement;
        statement = CCJSqlParserUtil.parse(sql);
        Select selectStatement = (Select) statement;

        // parse table name
        TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
        List<String> tableList = tablesNamesFinder.getTableList(selectStatement);
        if (tableList.size() != 1) {
            throw new ConsoleMessageException("Please provide only one table name.");
        }
        table.setTableName(tableList.get(0));

        // parse where clause
        PlainSelect selectBody = (PlainSelect) selectStatement.getSelectBody();
        if (selectBody.getOrderByElements() != null) {
            throw new ConsoleMessageException("The order clause is not supported.");
        }
        if (selectBody.getGroupBy() != null) {
            throw new ConsoleMessageException("The group clause is not supported.");
        }
        if (selectBody.getHaving() != null) {
            throw new ConsoleMessageException("The having clause is not supported.");
        }
        if (selectBody.getJoins() != null) {
            throw new ConsoleMessageException("The join clause is not supported.");
        }
        if (selectBody.getTop() != null) {
            throw new ConsoleMessageException("The top clause is not supported.");
        }
        if (selectBody.getDistinct() != null) {
            throw new ConsoleMessageException("The distinct clause is not supported.");
        }
        Expression expr = selectBody.getWhere();
        handleExpression(condition, expr);

        // parse select item
        List<SelectItem> selectItems = selectBody.getSelectItems();
        for (SelectItem item : selectItems) {
            if (item instanceof SelectExpressionItem) {
                SelectExpressionItem selectExpressionItem = (SelectExpressionItem) item;
                Expression expression = selectExpressionItem.getExpression();
                if (expression instanceof Function) {
                    Function func = (Function) expression;
                    throw new ConsoleMessageException(
                            "The " + func.getName() + " function is not supported.");
                }
            }
            selectColumns.add(item.toString());
        }
    }

    private static void checkExpression(Expression expression) throws ConsoleMessageException {
        if (expression instanceof OrExpression) {
            throw new ConsoleMessageException("The OrExpression is not supported.");
        }
        if (expression instanceof NotExpression) {
            throw new ConsoleMessageException("The NotExpression is not supported.");
        }
        if (expression instanceof InExpression) {
            throw new ConsoleMessageException("The InExpression is not supported.");
        }
        if (expression instanceof LikeExpression) {
            logger.debug("The LikeExpression is not supported.");
            throw new ConsoleMessageException("The LikeExpression is not supported.");
        }
        if (expression instanceof SubSelect) {
            throw new ConsoleMessageException("The SubSelect is not supported.");
        }
        if (expression instanceof IsNullExpression) {
            throw new ConsoleMessageException("The IsNullExpression is not supported.");
        }
    }

    private static Condition handleExpression(Condition condition, Expression expr)
            throws ConsoleMessageException {
        if (expr instanceof BinaryExpression) {
            condition = getWhereClause((BinaryExpression) (expr), condition);
        }
        checkExpression(expr);
        Map<String, Map<ConditionOperator, String>> conditions = condition.getConditions();
        Set<String> keys = conditions.keySet();
        for (String key : keys) {
            Map<ConditionOperator, String> value = conditions.get(key);
            ConditionOperator operation = value.keySet().iterator().next();
            String itemValue = value.values().iterator().next();
            String newValue = trimQuotes(itemValue);
            value.put(operation, newValue);
            conditions.put(key, value);
        }
        condition.setConditions(conditions);
        return condition;
    }

    public static String trimQuotes(String str) {
        char[] value = str.toCharArray();
        int len = value.length;
        int st = 1;
        char[] val = value; /* avoid getfield opcode */

        while ((st < len) && (val[st] == '"' || val[st] == '\'')) {
            st++;
        }
        while ((st < len) && (val[len - 1] == '"' || val[len - 1] == '\'')) {
            len--;
        }
        String string = ((st > 1) || (len < value.length)) ? str.substring(st, len) : str;
        return string;
    }

    public static void parseUpdate(String sql, Table table, Entry entry, Condition condition)
            throws JSQLParserException, ConsoleMessageException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        Update update = (Update) statement;

        // parse table name
        List<net.sf.jsqlparser.schema.Table> tables = update.getTables();
        String tableName = tables.get(0).getName();
        table.setTableName(tableName);

        // parse columns
        List<Column> columns = update.getColumns();
        List<Expression> expressions = update.getExpressions();
        int size = expressions.size();
        String[] values = new String[size];
        for (int i = 0; i < size; i++) {
            values[i] = expressions.get(i).toString();
        }
        for (int i = 0; i < columns.size(); i++) {
            entry.getFieldNameToValue()
                    .put(trimQuotes(columns.get(i).toString()), trimQuotes(values[i]));
        }

        // parse where clause
        Expression where = update.getWhere();
        if (where != null) {
            BinaryExpression expr2 = (BinaryExpression) (where);
            handleExpression(condition, expr2);
        }
    }

    public static void parseRemove(String sql, Table table, Condition condition)
            throws JSQLParserException, ConsoleMessageException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        Delete delete = (Delete) statement;

        // parse table name
        net.sf.jsqlparser.schema.Table sqlTable = delete.getTable();
        table.setTableName(sqlTable.getName());

        // parse where clause
        Expression where = delete.getWhere();
        if (where != null) {
            BinaryExpression expr = (BinaryExpression) (where);
            handleExpression(condition, expr);
        }
    }

    private static Condition getWhereClause(Expression expr, Condition condition)
            throws ConsoleMessageException {
        Set<String> keySet = new HashSet<>();
        Set<String> conflictKeys = new HashSet<>();
        Set<String> unsupportedConditions = new HashSet<>();
        expr.accept(
                new ExpressionVisitorAdapter() {
                    @Override
                    protected void visitBinaryExpression(BinaryExpression expr) {
                        if (expr instanceof ComparisonOperator) {
                            String key = trimQuotes(expr.getLeftExpression().toString());
                            if (keySet.contains(key)) {
                                conflictKeys.add(key);
                            }
                            keySet.add(key);
                            String operation = expr.getStringExpression();
                            String value = trimQuotes(expr.getRightExpression().toString());
                            switch (operation) {
                                case "=":
                                    condition.EQ(key, value);
                                    break;
                                case "!=":
                                    condition.NE(key, value);
                                    break;
                                case ">":
                                    condition.GT(key, value);
                                    break;
                                case ">=":
                                    condition.GE(key, value);
                                    break;
                                case "<":
                                    condition.LT(key, value);
                                    break;
                                case "<=":
                                    condition.LE(key, value);
                                    break;
                                default:
                                    break;
                            }
                        } else {
                            try {
                                checkExpression(expr);
                            } catch (ConsoleMessageException e) {
                                unsupportedConditions.add(e.getMessage());
                            }
                        }
                        super.visitBinaryExpression(expr);
                    }
                });
        if (!conflictKeys.isEmpty()) {
            throw new ConsoleMessageException(
                    "Wrong condition! There cannot be the same field in the same condition! The conflicting field is: "
                            + conflictKeys.toString());
        }
        if (!unsupportedConditions.isEmpty()) {
            throw new ConsoleMessageException(
                    "Wrong condition! Find unsupported conditions! message: "
                            + unsupportedConditions.toString());
        }
        return condition;
    }

    public static void invalidSymbol(String sql) throws ConsoleMessageException {
        if (sql.contains("；")) {
            throw new ConsoleMessageException("SyntaxError: Unexpected Chinese semicolon.");
        } else if (sql.contains("“")
                || sql.contains("”")
                || sql.contains("‘")
                || sql.contains("’")) {
            throw new ConsoleMessageException("SyntaxError: Unexpected Chinese quotes.");
        } else if (sql.contains("，")) {
            throw new ConsoleMessageException("SyntaxError: Unexpected Chinese comma.");
        }
    }
}
