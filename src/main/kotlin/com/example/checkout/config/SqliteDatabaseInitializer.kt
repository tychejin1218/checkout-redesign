package com.example.checkout.config

import org.springframework.core.io.Resource
import org.springframework.core.io.support.EncodedResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import java.sql.Connection
import javax.sql.DataSource

/**
 * SQLite DB 하나를 초기화 스크립트 직후 상태로 되돌린다.
 *
 * 모든 테이블을 만든 순서의 역순으로 지운 뒤 스크립트를 다시 실행한다. 외래 키가 켜져 있으면
 * 참조되는 테이블을 먼저 지울 수 없으므로, 나중에 만든 테이블(참조하는 쪽)부터 지운다. 커넥션 풀이 파일을 열고 있어서 파일을 지우는 대신 테이블을 지운다.
 * 레거시 스크립트는 `CREATE TABLE` 에 `IF NOT EXISTS` 가 없어 빈 DB 에서만 실행된다.
 */
class SqliteDatabaseInitializer(
    private val dataSource: DataSource,
    private val scripts: List<Resource>,
) {
    fun reset() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            dropAllTables(connection)
            scripts.forEach { ScriptUtils.executeSqlScript(connection, EncodedResource(it, Charsets.UTF_8)) }
            connection.commit()
        }
    }

    private fun dropAllTables(connection: Connection) {
        val tableNames =
            connection.createStatement().use { statement ->
                statement.executeQuery(USER_TABLES_QUERY).use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
        connection.createStatement().use { statement ->
            tableNames.forEach { statement.executeUpdate("DROP TABLE \"$it\"") }
        }
    }

    companion object {
        private const val USER_TABLES_QUERY =
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY rowid DESC"
    }
}
