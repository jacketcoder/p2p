package com.soriole.dfsnode.repository;

import com.soriole.dfsnode.model.db.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * @author github.com/bipinkh
 * created on : 26 Jul 2018
 */
public interface TransactionRepository extends JpaRepository<Transaction,Long> {
}