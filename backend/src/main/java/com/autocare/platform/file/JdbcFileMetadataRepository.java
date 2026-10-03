package com.autocare.platform.file;

import java.sql.Statement;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcFileMetadataRepository implements FileMetadataRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public JdbcFileMetadataRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(15);
    }
    @Override public long saveClean(Actor actor, String key, String type, long size) {
        return tx.execute(status -> {
            var holder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO file_object "
                    + "(owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES (?,?,?,?,?,'CLEAN')",
                    Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, actor.type());
                statement.setLong(2, actor.id());
                statement.setString(3, key);
                statement.setString(4, type);
                statement.setLong(5, size);
                return statement;
            }, holder);
            Number id = holder.getKey();
            if (id == null || id.longValue() <= 0) throw new IllegalStateException("Missing file ID");
            return id.longValue();
        });
    }
    @Override public Optional<Metadata> findOwned(Actor actor, long fileId) {
        return jdbc.query("SELECT id,object_key,content_type,size_bytes,scan_status FROM file_object "
            + "WHERE id=? AND owner_type=? AND owner_id=? AND is_deleted=0",
            (rs, row) -> new Metadata(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)),
            fileId, actor.type(), actor.id()).stream().findFirst();
    }
}
