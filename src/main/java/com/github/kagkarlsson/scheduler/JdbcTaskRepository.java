package com.github.kagkarlsson.scheduler;

import com.github.kagkarlsson.jdbc.JdbcRunner;
import com.github.kagkarlsson.jdbc.Mappers;
import com.github.kagkarlsson.jdbc.ResultSetMapper;
import com.github.kagkarlsson.jdbc.SQLRuntimeException;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcTaskRepository implements TaskRepository {
	private static final Logger LOG = LoggerFactory.getLogger(JdbcTaskRepository.class);
	private static final int MAX_DUE_RESULTS = 10_000;
	private final TaskResolver taskResolver;
	private final SchedulerName schedulerSchedulerName;
	private final JdbcRunner jdbcRunner;

	public JdbcTaskRepository(DataSource dataSource, TaskResolver taskResolver, SchedulerName schedulerSchedulerName) {
		this.taskResolver = taskResolver;
		this.schedulerSchedulerName = schedulerSchedulerName;
		this.jdbcRunner = new JdbcRunner(dataSource);
	}

	@Override
	public boolean createIfNotExists(Execution execution) {
		try {
			jdbcRunner.execute(
					"insert into scheduled_tasks(task_name, task_instance, execution_time, picked) values(?, ?, ?, ?)",
					(PreparedStatement p) -> {
						p.setString(1, execution.taskInstance.getTask().getName());
						p.setString(2, execution.taskInstance.getId());
						p.setTimestamp(3, Timestamp.valueOf(execution.executionTime));
						p.setBoolean(4, false);
					});
			return true;

		} catch (SQLRuntimeException e) {
			LOG.debug("Exception when inserting execution. Assuming it to be a constraint violation.", e);
			final Boolean exists = jdbcRunner.query("select 1 from scheduled_tasks where task_name = ? and task_instance = ?",
					ps -> {
						ps.setString(1, execution.taskInstance.getTaskName());
						ps.setString(2, execution.taskInstance.getId());
					},
					Mappers.NON_EMPTY_RESULTSET);
			if (!exists) {
				throw new RuntimeException("Failed to add new execution.", e);
			}
			LOG.debug("Exception was due to a constraint violation. Another thread inserted the execution.");
			return false;
		}
	}

	@Override
	public List<Execution> getDue(LocalDateTime now) {
		return getDue(now, MAX_DUE_RESULTS);
	}

	public List<Execution> getDue(LocalDateTime now, int limit) {
		return jdbcRunner.query(
				"select * from scheduled_tasks where picked = ? and execution_time <= ? order by execution_time asc",
				(PreparedStatement p) -> {
					p.setBoolean(1, false);
					p.setTimestamp(2, Timestamp.valueOf(now));
					p.setMaxRows(limit);
				},
				new ExecutionResultSetMapper()
		);
	}

	@Override
	public void remove(Execution execution) {

		final int removed = jdbcRunner.execute("delete from scheduled_tasks where task_name = ? and task_instance = ?",
				ps -> {
					ps.setString(1, execution.taskInstance.getTaskName());
					ps.setString(2, execution.taskInstance.getId());
				}
		);

		if (removed != 1) {
			throw new RuntimeException("Expected one execution to be removed, but removed " + removed + ". Indicates a bug.");
		}
	}

	@Override
	public void reschedule(Execution execution, LocalDateTime nextExecutionTime) {
		final int updated = jdbcRunner.execute(
				"update scheduled_tasks set " +
						"picked = ?, " +
						"picked_by = ?, " +
						"execution_time = ? " +
						"where task_name = ? " +
						"and task_instance = ?",
				ps -> {
					ps.setBoolean(1, false);
					ps.setString(2, null);
					ps.setTimestamp(3, Timestamp.valueOf(nextExecutionTime));
					ps.setString(4, execution.taskInstance.getTaskName());
					ps.setString(5, execution.taskInstance.getId());
				});

		if (updated != 1) {
			throw new RuntimeException("Expected one execution to be updated, but updated " + updated + ". Indicates a bug.");
		}
	}

	@Override
	public boolean pick(Execution e, LocalDateTime timePicked) {
		final int updated = jdbcRunner.execute(
				"update scheduled_tasks set picked = ?, picked_by = ?, last_heartbeat = ? " +
						"where picked = ? " +
						"and execution_time = ? " +
						"and task_name = ? " +
						"and task_instance = ?",
				ps -> {
					ps.setBoolean(1, true);
					ps.setString(2, schedulerSchedulerName.getName());
					ps.setTimestamp(3, Timestamp.valueOf(timePicked));
					ps.setBoolean(4, false);
					ps.setTimestamp(5, Timestamp.valueOf(e.executionTime));
					ps.setString(6, e.taskInstance.getTaskName());
					ps.setString(7, e.taskInstance.getId());
				});

		if (updated == 0) {
			LOG.trace("Failed to pick execution. It must have been picked by another scheduler.", e);
			return false;
		} else if (updated == 1) {
			return true;
		} else {
			throw new IllegalStateException("Updated multiple rows when picking single execution. Should never happen since name and id is primary key. Execution: " + e);
		}
	}

	@Override
	public List<Execution> getOldExecutions(LocalDateTime olderThan) {
		return jdbcRunner.query(
				"select * from scheduled_tasks where picked = ? and last_heartbeat <= ? order by last_heartbeat asc",
				(PreparedStatement p) -> {
					p.setBoolean(1, true);
					p.setTimestamp(2, Timestamp.valueOf(olderThan));
				},
				new ExecutionResultSetMapper()
		);
	}

	@Override
	public void updateHeartbeat(Execution e, LocalDateTime newHeartbeat) {

		final int updated = jdbcRunner.execute(
				"update scheduled_tasks set last_heartbeat = ? " +
						"where execution_time = ? " +
						"and task_name = ? " +
						"and task_instance = ?",
				ps -> {
					ps.setTimestamp(1, Timestamp.valueOf(newHeartbeat));
					ps.setTimestamp(2, Timestamp.valueOf(e.executionTime));
					ps.setString(3, e.taskInstance.getTaskName());
					ps.setString(4, e.taskInstance.getId());
				});

		if (updated == 0) {
			LOG.trace("Did not update heartbeat. Execution must have been removed or rescheduled.", e);
		} else {
			if (updated > 1) {
				LOG.warn("Update multiple rows when updating heartbeat for execution: " + e);
			}
			LOG.debug("Updated heartbeat for execution: " + e);
		}
	}

	public Optional<Execution> getExecution(TaskInstance taskInstance) {
		final List<Execution> executions = jdbcRunner.query(
				"select * from scheduled_tasks where task_name = ? and task_instance = ?",
				(PreparedStatement p) -> {
					p.setString(1, taskInstance.getTaskName());
					p.setString(2, taskInstance.getId());
				},
				new ExecutionResultSetMapper()
		);
		if (executions.size() > 1) {
			throw new RuntimeException("Found more than one matching execution for taskInstance: " + taskInstance);
		}

		return executions.size() == 1 ? Optional.ofNullable(executions.get(0)) : Optional.<Execution>empty();
	}

	private class ExecutionResultSetMapper implements ResultSetMapper<List<Execution>> {
		@Override
		public List<Execution> map(ResultSet rs) throws SQLException {

			List<Execution> executions = new ArrayList<>();
			while (rs.next()) {
				String taskName = rs.getString("task_name");
				Task task = taskResolver.resolve(taskName);

				if (task == null) {
					continue;
				}

				String taskInstance = rs.getString("task_instance");

				LocalDateTime executionTime = rs.getTimestamp("execution_time").toLocalDateTime();

				boolean picked = rs.getBoolean("picked");
				final String pickedBy = rs.getString("picked_by");
				final Optional<Timestamp> lastHeartbeatTimestamp = Optional.ofNullable(rs.getTimestamp("last_heartbeat"));
				LocalDateTime lastHeartbeat = lastHeartbeatTimestamp.map(Timestamp::toLocalDateTime).orElse(null);
				executions.add(new Execution(executionTime, new TaskInstance(task, taskInstance), picked, pickedBy, lastHeartbeat));
			}
			return executions;
		}
	}
}