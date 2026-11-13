package com.carenexus.api.billing.repository;

import com.carenexus.api.billing.model.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Saved payment methods. "Usable" means {@code isActive AND NOT isDeleted}: the rows an owner sees and can pay
 * with. An owner has only a handful of methods, so the lists are not paged.
 */
@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Integer> {

    /** The owner's usable methods, default first, then newest first. */
    @Query("SELECT m FROM PaymentMethod m WHERE m.systemId = :systemId AND m.isActive = true AND m.isDeleted = false "
            + "ORDER BY m.isDefault DESC, m.createdAt DESC, m.methodId DESC")
    List<PaymentMethod> findUsableBySystemId(@Param("systemId") String systemId);

    @Query("SELECT COUNT(m) FROM PaymentMethod m WHERE m.systemId = :systemId AND m.isActive = true "
            + "AND m.isDeleted = false")
    long countUsableBySystemId(@Param("systemId") String systemId);

    /** A method of this owner, whatever its state (also retired ones, for the payments that used them). */
    Optional<PaymentMethod> findByMethodIdAndSystemId(Integer methodId, String systemId);

    @Query("SELECT m FROM PaymentMethod m WHERE m.methodId = :methodId AND m.systemId = :systemId "
            + "AND m.isActive = true AND m.isDeleted = false")
    Optional<PaymentMethod> findUsableByMethodIdAndSystemId(@Param("methodId") Integer methodId,
                                                            @Param("systemId") String systemId);

    @Query("SELECT m FROM PaymentMethod m WHERE m.systemId = :systemId AND m.isDefault = true "
            + "AND m.isActive = true AND m.isDeleted = false")
    Optional<PaymentMethod> findDefaultBySystemId(@Param("systemId") String systemId);

    /**
     * Transaction-scoped advisory lock on one owner's saved methods: every create, default swap and delete of that
     * owner runs one after the other (also the very first method, when there is no row to lock yet). Held until
     * the surrounding transaction ends.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext('carenexus.payment-methods'), hashtext(:systemId))",
            nativeQuery = true)
    Integer lockOwner(@Param("systemId") String systemId);

    /**
     * Clears the default flag of every method of the owner. Run it before marking another method as default:
     * the database allows one usable default per owner. Returns the number of rows changed. The statement is
     * executed at once and the persistence context is cleared, so reload entities read before the call.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PaymentMethod m SET m.isDefault = false WHERE m.systemId = :systemId AND m.isDefault = true")
    int clearDefault(@Param("systemId") String systemId);
}
