package io.tapdata.flow.engine.V2.node.hazelcast.data.pdk;

import com.tapdata.constant.Log4jUtil;
import com.tapdata.constant.MilestoneUtil;
import com.tapdata.entity.TapdataShareLogEvent;
import com.tapdata.entity.dataflow.SyncProgress;
import com.tapdata.entity.task.ExistsDataProcessEnum;
import com.tapdata.entity.task.context.DataProcessorContext;
import com.tapdata.tm.commons.dag.Node;
import com.tapdata.tm.commons.dag.nodes.DatabaseNode;
import com.tapdata.tm.commons.dag.nodes.TableNode;
import io.tapdata.aspect.*;
import io.tapdata.aspect.utils.AspectUtils;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.event.ddl.TapDDLEvent;
import io.tapdata.entity.event.ddl.index.TapCreateIndexEvent;
import io.tapdata.entity.event.ddl.table.*;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapIndex;
import io.tapdata.entity.schema.TapIndexField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.simplify.pretty.ClassHandlers;
import io.tapdata.flow.engine.V2.common.task.SyncTypeEnum;
import io.tapdata.flow.engine.V2.exception.node.NodeException;
import io.tapdata.milestone.MilestoneStage;
import io.tapdata.milestone.MilestoneStatus;
import io.tapdata.pdk.apis.entity.merge.MergeInfo;
import io.tapdata.pdk.apis.entity.merge.MergeTableProperties;
import io.tapdata.pdk.apis.functions.PDKMethod;
import io.tapdata.pdk.apis.functions.connector.target.*;
import io.tapdata.pdk.core.api.ConnectorNode;
import io.tapdata.pdk.core.entity.params.PDKMethodInvoker;
import io.tapdata.pdk.core.monitor.PDKInvocationMonitor;
import io.tapdata.pdk.core.utils.LoggerUtils;
import io.tapdata.schema.TapTableMap;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.tapdata.entity.simplify.TapSimplify.*;

/**
 * @author jackin
 * @date 2022/2/22 2:33 PM
 **/
public class HazelcastTargetPdkDataNode extends HazelcastTargetPdkBaseNode {
	private static final String TAG = HazelcastTargetPdkDataNode.class.getSimpleName();
	public static final int MAX_INDEX_FIELDS_COUNT = 10;
	private final Logger logger = LogManager.getLogger(HazelcastTargetPdkDataNode.class);
	private ClassHandlers ddlEventHandlers;

	public HazelcastTargetPdkDataNode(DataProcessorContext dataProcessorContext) {
		super(dataProcessorContext);
	}

	@Override
	protected void doInit(@NotNull Context context) throws Exception {
		try {
			super.doInit(context);
			Node<?> node = dataProcessorContext.getNode();
			if (node instanceof TableNode) {
				tableName = ((TableNode) node).getTableName();
				updateConditionFields = ((TableNode) node).getUpdateConditionFields();
				writeStrategy = ((TableNode) node).getWriteStrategy();
			}
			initTargetDB();
			// MILESTONE-INIT_TRANSFORMER-FINISH
			TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.INIT_TRANSFORMER, MilestoneStatus.FINISH);
			MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.INIT_TRANSFORMER, MilestoneStatus.FINISH);
		} catch (Exception e) {
			// MILESTONE-INIT_TRANSFORMER-ERROR
			TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.INIT_TRANSFORMER, MilestoneStatus.ERROR, logger);
			MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.INIT_TRANSFORMER, MilestoneStatus.ERROR, e.getMessage() + "\n" + Log4jUtil.getStackString(e));
			throw e;
		}
		ddlEventHandlers = new ClassHandlers();
		ddlEventHandlers.register(TapNewFieldEvent.class, this::executeNewFieldFunction);
		ddlEventHandlers.register(TapAlterFieldNameEvent.class, this::executeAlterFieldNameFunction);
		ddlEventHandlers.register(TapAlterFieldAttributesEvent.class, this::executeAlterFieldAttrFunction);
		ddlEventHandlers.register(TapDropFieldEvent.class, this::executeDropFieldFunction);
		ddlEventHandlers.register(TapCreateTableEvent.class, this::executeCreateTableFunction);
		ddlEventHandlers.register(TapDropTableEvent.class, tapDropTableEvent -> {
			// only execute start function aspect so that it would be cheated as input
			AspectUtils.executeAspect(new DropTableFuncAspect()
					.dropTableEvent(tapDropTableEvent)
					.connectorContext(getConnectorNode().getConnectorContext())
					.dataProcessorContext(dataProcessorContext).state(DropTableFuncAspect.STATE_START));
			logger.warn("Drop table event will be ignored at sink node: " + tapDropTableEvent.getTableId());
			obsLogger.warn("Drop table event will be ignored at sink node: " + tapDropTableEvent.getTableId());
			return null;
		});
	}

	private void initTargetDB() {
		TapTableMap<String, TapTable> tapTableMap = dataProcessorContext.getTapTableMap();
		Node<?> node = dataProcessorContext.getNode();
		ExistsDataProcessEnum existsDataProcessEnum = getExistsDataProcess(node);
		SyncProgress syncProgress = initSyncProgress(dataProcessorContext.getTaskDto().getAttrs());
		if (null != syncProgress) return;
		for (String tableId : tapTableMap.keySet()) {
			if (!isRunning()) {
				break;
			}
			TapTable tapTable = tapTableMap.get(tableId);
			if (null == tapTable) {
				throw new NodeException("Init target node failed, table \"" + tableId + "\"'s schema is null").context(getDataProcessorContext());
			}
			dropTable(existsDataProcessEnum, tableId);
			createTable(tapTable);
			clearData(existsDataProcessEnum, tableId);
			createTargetIndex(node, tableId, tapTable);
		}
	}

	private void createTargetIndex(Node node, String tableId, TapTable tapTable) {
		CreateIndexFunction createIndexFunction = getConnectorNode().getConnectorFunctions().getCreateIndexFunction();
		if (null == createIndexFunction) {
			return;
		}

		AtomicReference<TapCreateIndexEvent> indexEvent = new AtomicReference<>();
		try {
			List<TapIndex> tapIndices = new ArrayList<>();
			TapIndex tapIndex = new TapIndex();
			List<TapIndexField> tapIndexFields = new ArrayList<>();
			List<String> updateConditionFields = null;
			if (node instanceof TableNode) {
				updateConditionFields = ((TableNode) node).getUpdateConditionFields();
			} else if (node instanceof DatabaseNode) {
				updateConditionFields = new ArrayList<>(tapTable.primaryKeys(true));
			}
			if (null == updateConditionFields) {
				logger.warn("Table " + tableId + " index fields is null, will not create index automatically");
				obsLogger.warn("Table " + tableId + " index fields is null, will not create index automatically");
				return;
			}
			if (updateConditionFields.size() > MAX_INDEX_FIELDS_COUNT) {
				logger.warn("Table " + tableId + " index field exceeds the maximum value of 10, the index will not be created automatically, please create it manually");
				obsLogger.warn("Table " + tableId + " index field exceeds the maximum value of 10, the index will not be created automatically, please create it manually");
				return;
			}
			if (CollectionUtils.isNotEmpty(updateConditionFields)) {
				updateConditionFields.forEach(field -> {
					TapIndexField tapIndexField = new TapIndexField();
					tapIndexField.setName(field);
					tapIndexField.setFieldAsc(true);
					tapIndexFields.add(tapIndexField);
				});
				tapIndex.setIndexFields(tapIndexFields);
				tapIndices.add(tapIndex);
				indexEvent.set(createIndexEvent(tableId, tapIndices));

				executeDataFuncAspect(CreateIndexFuncAspect.class, () -> new CreateIndexFuncAspect()
						.table(tapTable)
						.connectorContext(getConnectorNode().getConnectorContext())
						.dataProcessorContext(dataProcessorContext)
						.createIndexEvent(indexEvent.get())
						.start(), createIndexFuncAspect -> PDKInvocationMonitor.invoke(getConnectorNode(),
						PDKMethod.TARGET_CREATE_INDEX,
						() -> createIndexFunction.createIndex(getConnectorNode().getConnectorContext(), tapTable, indexEvent.get()), TAG));
			}
		} catch (Throwable throwable) {
			NodeException nodeException = new NodeException(throwable).context(getDataProcessorContext());
			if (null != indexEvent.get()) {
				nodeException.event(indexEvent.get());
			}
			logger.error(nodeException.getMessage(), nodeException);
			obsLogger.error(nodeException.getMessage(), nodeException);
			throw nodeException;
		}
	}

	private void clearData(ExistsDataProcessEnum existsDataProcessEnum, String tableId) {
		if (SyncTypeEnum.CDC == syncType || existsDataProcessEnum != ExistsDataProcessEnum.REMOVE_DATE) return;

		AtomicReference<TapClearTableEvent> tapClearTableEvent = new AtomicReference<>();
		try {
			ClearTableFunction clearTableFunction = getConnectorNode().getConnectorFunctions().getClearTableFunction();
			Optional.ofNullable(clearTableFunction).ifPresent(func -> {
				tapClearTableEvent.set(clearTableEvent(tableId));
				executeDataFuncAspect(ClearTableFuncAspect.class, () -> new ClearTableFuncAspect()
						.clearTableEvent(tapClearTableEvent.get())
						.connectorContext(getConnectorNode().getConnectorContext())
						.dataProcessorContext(dataProcessorContext)
						.start(), clearTableFuncAspect ->
						PDKInvocationMonitor.invoke(getConnectorNode(), PDKMethod.TARGET_CLEAR_TABLE, () -> func.clearTable(getConnectorNode().getConnectorContext(), tapClearTableEvent.get()), TAG));
			});
		} catch (Throwable throwable) {
			NodeException nodeException = new NodeException(throwable).context(getDataProcessorContext());
			if (null != tapClearTableEvent.get()) {
				nodeException.event(tapClearTableEvent.get());
			}
			logger.error(nodeException.getMessage(), nodeException);
			obsLogger.error(nodeException.getMessage(), nodeException);
			throw nodeException;
		}
	}

	private void createTable(TapTable tapTable) {
		AtomicReference<TapCreateTableEvent> tapCreateTableEvent = new AtomicReference<>();
		try {
			CreateTableFunction createTableFunction = getConnectorNode().getConnectorFunctions().getCreateTableFunction();
			CreateTableV2Function createTableV2Function = getConnectorNode().getConnectorFunctions().getCreateTableV2Function();
			if (createTableV2Function != null || createTableFunction != null) {
				handleTapTablePrimaryKeys(tapTable);
				tapCreateTableEvent.set(createTableEvent(tapTable));
				executeDataFuncAspect(CreateTableFuncAspect.class, () -> new CreateTableFuncAspect()
						.createTableEvent(tapCreateTableEvent.get())
						.connectorContext(getConnectorNode().getConnectorContext())
						.dataProcessorContext(dataProcessorContext)
						.start(), (createTableFuncAspect ->
						PDKInvocationMonitor.invoke(getConnectorNode(), PDKMethod.TARGET_CREATE_TABLE, () -> {
							if (createTableV2Function != null) {
								CreateTableOptions createTableOptions = createTableV2Function.createTable(getConnectorNode().getConnectorContext(), tapCreateTableEvent.get());
								if (createTableFuncAspect != null)
									createTableFuncAspect.createTableOptions(createTableOptions);
							} else {
								createTableFunction.createTable(getConnectorNode().getConnectorContext(), tapCreateTableEvent.get());
							}
						}, TAG)));
			} else {
				// only execute start function aspect so that it would be cheated as input
				AspectUtils.executeAspect(new CreateTableFuncAspect()
						.createTableEvent(tapCreateTableEvent.get())
						.connectorContext(getConnectorNode().getConnectorContext())
						.dataProcessorContext(dataProcessorContext).state(NewFieldFuncAspect.STATE_START));
			}
		} catch (Throwable throwable) {
			NodeException nodeException = new NodeException(throwable).context(processorBaseContext);
			if (null != tapCreateTableEvent.get()) {
				nodeException.event(tapCreateTableEvent.get());
			}
			logger.error(nodeException.getMessage(), nodeException);
			obsLogger.error(nodeException.getMessage(), nodeException);
			throw nodeException;
		}

	}

	private void dropTable(ExistsDataProcessEnum existsDataProcessEnum, String tableId) {
		if (SyncTypeEnum.CDC == syncType || existsDataProcessEnum != ExistsDataProcessEnum.DROP_TABLE) return;

		AtomicReference<TapDropTableEvent> tapDropTableEvent = new AtomicReference<>();
		try {
			DropTableFunction dropTableFunction = getConnectorNode().getConnectorFunctions().getDropTableFunction();
			if (dropTableFunction != null) {
				tapDropTableEvent.set(dropTableEvent(tableId));
				executeDataFuncAspect(DropTableFuncAspect.class, () -> new DropTableFuncAspect()
						.dropTableEvent(tapDropTableEvent.get())
						.connectorContext(getConnectorNode().getConnectorContext())
						.dataProcessorContext(dataProcessorContext)
						.start(), (dropTableFuncAspect ->
						PDKInvocationMonitor.invoke(getConnectorNode(), PDKMethod.TARGET_DROP_TABLE, () -> dropTableFunction.dropTable(getConnectorNode().getConnectorContext(), tapDropTableEvent.get()), TAG)));
			} else {
				// only execute start function aspect so that it would be cheated as input
				AspectUtils.executeAspect(new DropTableFuncAspect()
						.dropTableEvent(tapDropTableEvent.get())
						.connectorContext(getConnectorNode().getConnectorContext())
						.dataProcessorContext(dataProcessorContext).state(NewFieldFuncAspect.STATE_START));
			}
		} catch (Throwable throwable) {
			NodeException nodeException = new NodeException(throwable).context(getDataProcessorContext());
			if (null != tapDropTableEvent.get()) {
				nodeException.event(tapDropTableEvent.get());
			}
			logger.error(nodeException.getMessage(), nodeException);
			obsLogger.error(nodeException.getMessage(), nodeException);
			throw nodeException;
		}

	}

	@Nullable
	private ExistsDataProcessEnum getExistsDataProcess(Node<?> node) {
		String existDataProcessMode = null;
		if (node instanceof TableNode) {
			existDataProcessMode = ((TableNode) node).getExistDataProcessMode();
		} else if (node instanceof DatabaseNode) {
			existDataProcessMode = ((DatabaseNode) node).getExistDataProcessMode();
		}
		existDataProcessMode = StringUtils.isNotBlank(existDataProcessMode) ? existDataProcessMode : "keepData";
		return ExistsDataProcessEnum.fromOption(existDataProcessMode);
	}

	@Override
	void processEvents(List<TapEvent> tapEvents) {
		dispatchTapRecordEvents(tapEvents,
				dispatchEntity -> {
					if (dispatchEntity.getCurrentTapEvent() instanceof TapRecordEvent && dispatchEntity.getLastTapEvent() instanceof TapRecordEvent) {
						return !((TapRecordEvent) dispatchEntity.getLastTapEvent()).getTableId().equals(((TapRecordEvent) dispatchEntity.getCurrentTapEvent()).getTableId());
					} else {
						return true;
					}
				},
				events -> {
					TapEvent firstEvent = events.get(0);
					if (firstEvent instanceof TapRecordEvent) {
						writeRecord(events);
					} else if (firstEvent instanceof TapDDLEvent) {
						writeDDL(events);
					}
				});
	}

	@Override
	void processShareLog(List<TapdataShareLogEvent> tapdataShareLogEvents) {
		throw new UnsupportedOperationException();
	}

	private void writeDDL(List<TapEvent> events) {
		List<TapDDLEvent> tapDDLEvents = new ArrayList<>();
		events.forEach(event -> {
			TapDDLEvent tapDDLEvent = (TapDDLEvent) event;
			tapDDLEvent.setTableId(getTgtTableNameFromTapEvent(tapDDLEvent));
			tapDDLEvents.add(tapDDLEvent);
		});
		for (TapDDLEvent tapDDLEvent : tapDDLEvents) {
			Object result = ddlEventHandlers.handle(tapDDLEvent);
			if (null == result) {
				logger.warn("DDL event: " + tapDDLEvent.getClass().getSimpleName() + " does not supported");
				obsLogger.warn("DDL event: " + tapDDLEvent.getClass().getSimpleName() + " does not supported");
			}
		}
		uploadDagService.compareAndSet(false, true);
	}

	private boolean executeNewFieldFunction(TapNewFieldEvent tapNewFieldEvent) {
		TapTable tapTable = dataProcessorContext.getTapTableMap().get(tapNewFieldEvent.getTableId());
		if (null == tapTable) {
			throw new NodeException("Get tap table failed, table id: " + tapNewFieldEvent.getTableId()).context(getDataProcessorContext());
		}
		LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
		if (MapUtils.isNotEmpty(nameFieldMap)) {
			List<TapField> newFields = tapNewFieldEvent.getNewFields();
			for (TapField newField : newFields) {
				String fieldName = newField.getName();
				TapField tapField = tapTable.getNameFieldMap().get(fieldName);
				if (null == tapField) {
					throw new NodeException("Set data type failed, tap table not contains field [" + fieldName + "]")
							.context(getDataProcessorContext()).events(Collections.singletonList(tapNewFieldEvent));
				}
				newField.setDataType(tapTable.getNameFieldMap().get(fieldName).getDataType());
			}
		}
		ConnectorNode connectorNode = getConnectorNode();
		NewFieldFunction function = connectorNode.getConnectorFunctions().getNewFieldFunction();
		PDKMethod pdkMethod = PDKMethod.NEW_FIELD;
		if (null == function) {
			// only execute start function aspect so that it would be cheated as input
			AspectUtils.executeAspect(new NewFieldFuncAspect()
					.newFieldEvent(tapNewFieldEvent)
					.connectorContext(getConnectorNode().getConnectorContext())
					.dataProcessorContext(dataProcessorContext).state(NewFieldFuncAspect.STATE_START));
			logger.warn("PDK connector " + connectorNode.getConnectorContext().getSpecification().getId() + " does not support " + pdkMethod);
			obsLogger.warn("PDK connector " + connectorNode.getConnectorContext().getSpecification().getId() + " does not support " + pdkMethod);
			return false;
		}
		try {
			executeDataFuncAspect(NewFieldFuncAspect.class, () -> new NewFieldFuncAspect()
					.newFieldEvent(tapNewFieldEvent)
					.connectorContext(connectorNode.getConnectorContext())
					.dataProcessorContext(dataProcessorContext)
					.start(), (newFieldFuncAspect ->
					PDKInvocationMonitor.invoke(connectorNode, pdkMethod,
							() -> function.newField(connectorNode.getConnectorContext(), tapNewFieldEvent), TAG)));
		} catch (Exception e) {
			throw new NodeException("Execute " + pdkMethod + " failed, error: " + e.getMessage(), e)
					.context(getDataProcessorContext()).event(tapNewFieldEvent);
		}
		return true;
	}

	private boolean executeAlterFieldNameFunction(TapAlterFieldNameEvent tapAlterFieldNameEvent) {
		ConnectorNode connectorNode = getConnectorNode();
		AlterFieldNameFunction function = connectorNode.getConnectorFunctions().getAlterFieldNameFunction();
		PDKMethod pdkMethod = PDKMethod.ALTER_FIELD_NAME;
		if (null == function) {
			// only execute start function aspect so that it would be cheated as input
			AspectUtils.executeAspect(new AlterFieldNameFuncAspect()
					.alterFieldNameEvent(tapAlterFieldNameEvent)
					.connectorContext(getConnectorNode().getConnectorContext())
					.dataProcessorContext(dataProcessorContext).state(AlterFieldNameFuncAspect.STATE_START));
			logger.warn("PDK connector " + connectorNode.getConnectorContext().getSpecification().getId() + " does not support " + pdkMethod);
			obsLogger.warn("PDK connector " + connectorNode.getConnectorContext().getSpecification().getId() + " does not support " + pdkMethod);
			return false;
		}
		try {
			executeDataFuncAspect(AlterFieldNameFuncAspect.class, () -> new AlterFieldNameFuncAspect()
					.alterFieldNameEvent(tapAlterFieldNameEvent)
					.connectorContext(connectorNode.getConnectorContext())
					.dataProcessorContext(dataProcessorContext)
					.start(), (alterFieldNameFuncAspect) ->
					PDKInvocationMonitor.invoke(connectorNode, PDKMethod.ALTER_FIELD_NAME,
							() -> function.alterFieldName(connectorNode.getConnectorContext(), tapAlterFieldNameEvent),
							TAG));
		} catch (Exception e) {
			throw new NodeException("Execute " + pdkMethod + " failed, error: " + e.getMessage(), e)
					.context(getDataProcessorContext()).event(tapAlterFieldNameEvent);
		}
		return true;
	}

	private boolean executeAlterFieldAttrFunction(TapAlterFieldAttributesEvent tapAlterFieldAttributesEvent) {
		TapTable tapTable = dataProcessorContext.getTapTableMap().get(tapAlterFieldAttributesEvent.getTableId());
		if (null == tapTable) {
			throw new NodeException("Get tap table failed, table id: " + tapAlterFieldAttributesEvent.getTableId())
					.context(getDataProcessorContext());
		}
		LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
		if (MapUtils.isNotEmpty(nameFieldMap)) {
			if (null != tapAlterFieldAttributesEvent.getDataTypeChange() && StringUtils.isNotBlank(tapAlterFieldAttributesEvent.getDataTypeChange().getAfter())) {
				String fieldName = tapAlterFieldAttributesEvent.getFieldName();
				String dataType = nameFieldMap.get(fieldName).getDataType();
				tapAlterFieldAttributesEvent.getDataTypeChange().setAfter(dataType);
			}
		}
		ConnectorNode connectorNode = getConnectorNode();
		AlterFieldAttributesFunction function = connectorNode.getConnectorFunctions().getAlterFieldAttributesFunction();
		PDKMethod pdkMethod = PDKMethod.ALTER_FIELD_ATTRIBUTES;
		if (null == function) {
			// only execute start function aspect so that it would be cheated as input
			AspectUtils.executeAspect(new AlterFieldAttributesFuncAspect()
					.alterFieldAttributesEvent(tapAlterFieldAttributesEvent)
					.connectorContext(getConnectorNode().getConnectorContext())
					.dataProcessorContext(dataProcessorContext).state(AlterFieldAttributesFuncAspect.STATE_START));
			logger.warn("PDK connector " + connectorNode.getConnectorContext().getSpecification().getId() + " does not support " + pdkMethod);
			obsLogger.warn("PDK connector " + connectorNode.getConnectorContext().getSpecification().getId() + " does not support " + pdkMethod);
			return false;
		}
		try {
			executeDataFuncAspect(AlterFieldAttributesFuncAspect.class, () -> new AlterFieldAttributesFuncAspect()
					.alterFieldAttributesEvent(tapAlterFieldAttributesEvent)
					.connectorContext(connectorNode.getConnectorContext())
					.dataProcessorContext(dataProcessorContext)
					.start(), (alterFieldAttributesFuncAspect ->
					PDKInvocationMonitor.invoke(connectorNode, PDKMethod.ALTER_FIELD_ATTRIBUTES,
							() -> function.alterFieldAttributes(connectorNode.getConnectorContext(), tapAlterFieldAttributesEvent),
							TAG)));
		} catch (Exception e) {
			throw new NodeException("Execute " + pdkMethod + " failed, error: " + e.getMessage(), e)
					.context(getDataProcessorContext()).event(tapAlterFieldAttributesEvent);
		}
		return true;
	}

	private boolean executeDropFieldFunction(TapDropFieldEvent tapDropFieldEvent) {
		ConnectorNode connectorNode = getConnectorNode();
		DropFieldFunction function = connectorNode.getConnectorFunctions().getDropFieldFunction();
		PDKMethod pdkMethod = PDKMethod.DROP_FIELD;
		if (null == function) {
			// only execute start function aspect so that it would be cheated as input
			AspectUtils.executeAspect(new DropFieldFuncAspect()
					.dropFieldEvent(tapDropFieldEvent)
					.connectorContext(getConnectorNode().getConnectorContext())
					.dataProcessorContext(dataProcessorContext).state(DropFieldFuncAspect.STATE_START));
			logger.warn("PDK connector " + connectorNode.getConnectorContext().getSpecification().getId() + " does not support " + pdkMethod);
			obsLogger.warn("PDK connector " + connectorNode.getConnectorContext().getSpecification().getId() + " does not support " + pdkMethod);
			return false;
		}
		try {
			executeDataFuncAspect(DropFieldFuncAspect.class, () -> new DropFieldFuncAspect()
					.dropFieldEvent(tapDropFieldEvent)
					.connectorContext(connectorNode.getConnectorContext())
					.dataProcessorContext(dataProcessorContext)
					.start(), (dropFieldFuncAspect ->
					PDKInvocationMonitor.invoke(connectorNode, PDKMethod.DROP_FIELD,
							() -> function.dropField(connectorNode.getConnectorContext(), tapDropFieldEvent),
							TAG)));
		} catch (Exception e) {
			throw new NodeException("Execute " + pdkMethod + " failed, error: " + e.getMessage(), e)
					.context(getDataProcessorContext()).event(tapDropFieldEvent);
		}
		return true;
	}

	private boolean executeCreateTableFunction(TapCreateTableEvent tapCreateTableEvent) {
		String tgtTableName = getTgtTableNameFromTapEvent(tapCreateTableEvent);
		TapTable tgtTapTable = dataProcessorContext.getTapTableMap().get(tgtTableName);
		createTable(tgtTapTable);
		return true;
	}

	private void writeRecord(List<TapEvent> events) {
		List<TapRecordEvent> tapRecordEvents = new ArrayList<>();
		events.forEach(event -> tapRecordEvents.add((TapRecordEvent) event));
		TapRecordEvent firstEvent = tapRecordEvents.get(0);
		String tableId = firstEvent.getTableId();
		String tgtTableName = getTgtTableNameFromTapEvent(firstEvent);
		if (StringUtils.isBlank(tgtTableName)) {
			throw new NodeException("Get target table name from event failed, event table id: " + tableId)
					.context(getDataProcessorContext()).events(events);
		}
		TapTable tapTable = dataProcessorContext.getTapTableMap().get(tgtTableName);
		handleTapTablePrimaryKeys(tapTable);
		events.forEach(this::addPropertyForMergeEvent);
		WriteRecordFunction writeRecordFunction = getConnectorNode().getConnectorFunctions().getWriteRecordFunction();
		PDKMethodInvoker pdkMethodInvoker = createPdkMethodInvoker();
		if (writeRecordFunction != null) {
			logger.debug("Write {} of record events, {}", tapRecordEvents.size(), LoggerUtils.targetNodeMessage(getConnectorNode()));
			try {
				try {
					executeDataFuncAspect(WriteRecordFuncAspect.class, () -> new WriteRecordFuncAspect()
							.recordEvents(tapRecordEvents)
							.table(tapTable)
							.connectorContext(getConnectorNode().getConnectorContext())
							.dataProcessorContext(dataProcessorContext)
							.start(), (writeRecordFuncAspect ->
							PDKInvocationMonitor.invoke(getConnectorNode(), PDKMethod.TARGET_WRITE_RECORD,
									pdkMethodInvoker.runnable(
											() -> writeRecordFunction.writeRecord(getConnectorNode().getConnectorContext(), tapRecordEvents, tapTable, writeListResult -> {
												Map<TapRecordEvent, Throwable> errorMap = writeListResult.getErrorMap();
												if (MapUtils.isNotEmpty(errorMap)) {
													TapRecordEvent lastErrorTapRecord = null;
													Throwable lastErrorThrowable = null;
													for (Map.Entry<TapRecordEvent, Throwable> tapRecordEventThrowableEntry : errorMap.entrySet()) {
														logger.warn(tapRecordEventThrowableEntry.getValue().getMessage() + "\n" + Log4jUtil.getStackString(tapRecordEventThrowableEntry.getValue()));
														logger.warn("Error record: " + tapRecordEventThrowableEntry.getKey());
														obsLogger.warn(tapRecordEventThrowableEntry.getValue().getMessage() + "\n" + Log4jUtil.getStackString(tapRecordEventThrowableEntry.getValue()));
														obsLogger.warn("Error record: " + tapRecordEventThrowableEntry.getKey());
														lastErrorTapRecord = tapRecordEventThrowableEntry.getKey();
														lastErrorThrowable = tapRecordEventThrowableEntry.getValue();
													}
													throw new RuntimeException(String.format("Write record %s failed", lastErrorTapRecord), lastErrorThrowable);
												}

												if (writeRecordFuncAspect != null)
													AspectUtils.accept(writeRecordFuncAspect.state(WriteRecordFuncAspect.STATE_WRITING).getConsumers(), tapRecordEvents, writeListResult);
												if (logger.isDebugEnabled()) {
													logger.debug("Wrote {} of record events, {}", tapRecordEvents.size(), LoggerUtils.targetNodeMessage(getConnectorNode()));
												}
											})
									)
							)));
				} finally {
					removePdkMethodInvoker(pdkMethodInvoker);
				}
			} catch (Exception e) {
				throw new NodeException(e).context(getDataProcessorContext()).events(events);
			}
		} else {
			throw new NodeException("PDK connector " + getConnectorNode().getConnectorContext().getSpecification().getId() + " does not support write record function")
					.context(getDataProcessorContext()).events(events);
		}
	}

	private void dispatchTapRecordEvents(List<TapEvent> tapEvents, Predicate<DispatchEntity> dispatchClause, Consumer<List<TapEvent>> consumer) {
		DispatchEntity dispatchEntity = new DispatchEntity();
		List<TapEvent> tempList = new ArrayList<>();
		for (TapEvent tapEvent : tapEvents) {
			if (tapEvent instanceof TapRecordEvent) {
				TapRecordEvent tapRecordEvent = (TapRecordEvent) tapEvent;
				if (null == dispatchEntity.getLastTapEvent()) {
					dispatchEntity.setLastTapEvent(tapRecordEvent);
				}
				dispatchEntity.setCurrentTapEvent(tapRecordEvent);
				if (null != dispatchClause && dispatchClause.test(dispatchEntity)) {
					if (CollectionUtils.isNotEmpty(tempList)) {
						consumer.accept(tempList);
						tempList.clear();
					}
					dispatchEntity.setLastTapEvent(tapRecordEvent);
				}
				tempList.add(tapEvent);
			} else if (tapEvent instanceof TapDDLEvent) {
				if (CollectionUtils.isNotEmpty(tempList)) {
					consumer.accept(tempList);
					tempList.clear();
				}
				tempList.add(tapEvent);
				consumer.accept(tempList);
				tempList.clear();
			}
		}
		if (CollectionUtils.isNotEmpty(tempList)) {
			consumer.accept(tempList);
			tempList.clear();
		}
	}

	private static class DispatchEntity {
		private TapEvent lastTapEvent;
		private TapEvent currentTapEvent;

		public TapEvent getLastTapEvent() {
			return lastTapEvent;
		}

		public void setLastTapEvent(TapEvent lastTapEvent) {
			this.lastTapEvent = lastTapEvent;
		}

		public TapEvent getCurrentTapEvent() {
			return currentTapEvent;
		}

		public void setCurrentTapEvent(TapEvent currentTapEvent) {
			this.currentTapEvent = currentTapEvent;
		}
	}

	private void addPropertyForMergeEvent(TapEvent tapEvent) {
		if (null == tapEvent) return;
		Object info = tapEvent.getInfo(MergeInfo.EVENT_INFO_KEY);
		if (!(info instanceof MergeInfo)) return;
		MergeInfo mergeInfo = (MergeInfo) info;
		if (CollectionUtils.isEmpty(updateConditionFields)) return;
		MergeTableProperties currentProperty = mergeInfo.getCurrentProperty();
		if (null == currentProperty) return;
		if (null == currentProperty.getMergeType()) {
			currentProperty.setMergeType(MergeTableProperties.MergeType.valueOf(writeStrategy));
		}
		if (currentProperty.getMergeType() == MergeTableProperties.MergeType.appendWrite) return;
		if (CollectionUtils.isEmpty(currentProperty.getJoinKeys())) {
			List<Map<String, String>> joinKeys = new ArrayList<>();
			for (String updateConditionField : updateConditionFields) {
				HashMap<String, String> joinKey = new HashMap<>();
				joinKey.put("source", updateConditionField);
				joinKey.put("target", updateConditionField);
				joinKeys.add(joinKey);
			}
			currentProperty.setJoinKeys(joinKeys);
		}
	}
}
