package de.metas.handlingunits.reservation;

import static de.metas.handlingunits.HUAssertions.assertThat;
import static de.metas.handlingunits.HUConditions.isAggregate;
import static de.metas.handlingunits.HUConditions.isNotAggregate;
import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.TEN;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.adempiere.test.AdempiereTestHelper;
import org.adempiere.test.AdempiereTestWatcher;
import org.adempiere.util.Services;
import org.assertj.core.api.Condition;
import org.compiere.model.I_C_UOM;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import de.metas.handlingunits.HuId;
import de.metas.handlingunits.IHUPackingMaterialsCollector;
import de.metas.handlingunits.IHandlingUnitsBL;
import de.metas.handlingunits.IHandlingUnitsDAO;
import de.metas.handlingunits.allocation.transfer.HUTransformService;
import de.metas.handlingunits.allocation.transfer.impl.LUTUProducerDestinationTestSupport;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.spi.IHUPackingMaterialCollectorSource;
import de.metas.handlingunits.storage.IHUProductStorage;
import de.metas.handlingunits.storage.IHUStorage;
import de.metas.order.OrderLineId;
import de.metas.product.ProductId;
import de.metas.quantity.Quantity;
import mockit.Mocked;

/*
 * #%L
 * de.metas.handlingunits.base
 * %%
 * Copyright (C) 2018 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public class HuReservationServiceTest
{
	@Rule
	public AdempiereTestWatcher adempiereTestWatcher = new AdempiereTestWatcher();

	private static final BigDecimal ELEVEN = TEN.add(ONE);
	private static final BigDecimal TWOHUNDRET = new BigDecimal("200");

	@Mocked
	private IHUPackingMaterialsCollector<IHUPackingMaterialCollectorSource> noopPackingMaterialsCollector;

	private LUTUProducerDestinationTestSupport data;

	private HuReservationService huReservationService;

	private IHandlingUnitsBL handlingUnitsBL;

	private I_C_UOM cuUOM;

	private IHandlingUnitsDAO handlingUnitsDAO;

	private HuReservationRepository huReservationRepository;

	@Before
	public void init()
	{
		AdempiereTestHelper.get().init();

		data = new LUTUProducerDestinationTestSupport();

		huReservationRepository = new HuReservationRepository();

		huReservationService = new HuReservationService(huReservationRepository);
		huReservationService.setHuTransformServiceSupplier(() -> HUTransformService.newInstance(data.helper.getHUContext()));

		cuUOM = data.helper.uomKg;

		handlingUnitsBL = Services.get(IHandlingUnitsBL.class);
		handlingUnitsDAO = Services.get(IHandlingUnitsDAO.class);

	}

	@Test
	public void makeReservation_from_aggregate_TU()
	{
		final I_M_HU lu = handlingUnitsBL.getTopLevelParent(data.mkAggregateHUWithTotalQtyCU("200"));

		final HuReservationRequest request = HuReservationRequest.builder()
				.salesOrderLineId(OrderLineId.ofRepoId(20))
				.huId(HuId.ofRepoId(lu.getM_HU_ID()))
				.qtyToReserve(Quantity.of(ONE, cuUOM))
				.productId(data.helper.pTomatoProductId)
				.build();

		// invoke the method under test
		final HuReservation result = huReservationService.makeReservation(request);

		assertThat(result).isNotNull();
		assertThat(result.getReservedQtySum().get().getAsBigDecimal()).isEqualByComparingTo("1");
		assertThat(result.getReservedQtySum().get().getUOMId()).isEqualTo(cuUOM.getC_UOM_ID());

		final Map<HuId, Quantity> vhuId2reservedQtys = result.getVhuId2reservedQtys();
		assertThat(vhuId2reservedQtys).hasSize(1);
		final HuId vhuId = vhuId2reservedQtys.entrySet().iterator().next().getKey();
		assertThat(vhuId2reservedQtys.get(vhuId).getAsBigDecimal()).isEqualByComparingTo(ONE);

		assertThatHuHasQty(lu,"200");

		final List<I_M_HU> includedHUs = handlingUnitsDAO.retrieveIncludedHUs(lu);
		//data.helper.commitAndDumpHU(lu);
		assertThat(includedHUs).hasSize(2); // one for the remaining "aggregated" TUs, one for the "real" TU that contains the reserved CU

		assertThat(includedHUs)
				.filteredOn(isAggregate())
				.hasSize(1)
				.allSatisfy(hu -> assertThatHuHasQty(hu, "160"));

		assertThat(includedHUs)
				.filteredOn(isNotAggregate())
				.hasSize(1)
				.allSatisfy(includedHU -> assertThatHuHasQty(includedHU, "40"))
				.allSatisfy(includedHU -> {
					final List<I_M_HU> includedCUs = handlingUnitsDAO.retrieveIncludedHUs(includedHU);
					assertThat(includedCUs).hasSize(2).filteredOn(hasQty("1"))
							.hasSize(1)
							.allMatch(I_M_HU::isReserved)
							.extracting(I_M_HU::getM_HU_ID)
							.allMatch(huId -> huId == vhuId.getRepoId());
					assertThat(includedCUs).hasSize(2).filteredOn(hasQty("39")).hasSize(1);
				});
	}

	@Test
	public void makeReservation_from_aggregate_TU_reserve_all()
	{
		// create one LU with 5 TUs with 40kg each
		final I_M_HU lu = handlingUnitsBL.getTopLevelParent(data.mkAggregateHUWithTotalQtyCU("200"));

		final HuReservationRequest firstRequest = HuReservationRequest.builder()
				.salesOrderLineId(OrderLineId.ofRepoId(20))
				.huId(HuId.ofRepoId(lu.getM_HU_ID()))
				.qtyToReserve(Quantity.of(TWOHUNDRET, cuUOM))
				.productId(data.helper.pTomatoProductId)
				.build();

		// invoke the method under test
		final HuReservation result = huReservationService.makeReservation(firstRequest);

		assertThat(result.getReservedQtySum().get().getAsBigDecimal()).isEqualByComparingTo(TWOHUNDRET);

		final Map<HuId, Quantity> vhuId2reservedQtys = result.getVhuId2reservedQtys();
		assertThat(vhuId2reservedQtys).hasSize(5);
		final HuId vhuId = vhuId2reservedQtys.entrySet().iterator().next().getKey();
		assertThat(vhuId2reservedQtys.get(vhuId).getAsBigDecimal()).isEqualByComparingTo("40");

		assertThatHuHasQty(lu, "200");

		final List<I_M_HU> includedHUs = handlingUnitsDAO.retrieveIncludedHUs(lu);
		assertThat(includedHUs).hasSize(5)
				.filteredOn(isNotAggregate()).hasSize(5)
				.allSatisfy(tu -> assertThatHuHasQty(tu, "40"))
				.allSatisfy(tu -> {
					final List<I_M_HU> includedCUs = handlingUnitsDAO.retrieveIncludedHUs(tu);
					assertThat(includedCUs).hasSize(1)
							.filteredOn(hasQty("40")).hasSize(1)
							.allMatch(I_M_HU::isReserved)
							.extracting(I_M_HU::getM_HU_ID)
							.allMatch(cuId -> vhuId2reservedQtys.containsKey(HuId.ofRepoId(cuId)));
				});
	}

	@Test
	public void makeReservation_from_aggregate_TU_was_already_reserved()
	{
		final I_M_HU lu = handlingUnitsBL.getTopLevelParent(data.mkAggregateHUWithTotalQtyCU("200"));

		final HuReservationRequest firstRequest = HuReservationRequest.builder()
				.salesOrderLineId(OrderLineId.ofRepoId(20))
				.huId(HuId.ofRepoId(lu.getM_HU_ID()))
				.qtyToReserve(Quantity.of(TWOHUNDRET, cuUOM))
				.productId(data.helper.pTomatoProductId)
				.build();

		// invoke the method under test
		final HuReservation firstResult = huReservationService.makeReservation(firstRequest);

		assertThat(firstResult.getReservedQtySum()).isPresent();
		assertThat(firstResult.getReservedQtySum().get().getAsBigDecimal()).isEqualByComparingTo(TWOHUNDRET); // guard

		final HuReservationRequest secondRequest = HuReservationRequest.builder()
				.salesOrderLineId(OrderLineId.ofRepoId(20))
				.huId(HuId.ofRepoId(lu.getM_HU_ID()))
				.qtyToReserve(Quantity.of(TWOHUNDRET, cuUOM))
				.productId(ProductId.ofRepoId(data.helper.pTomato.getM_Product_ID()))
				.build();

		// invoke the method under test
		final HuReservation secondResult = huReservationService.makeReservation(secondRequest);

		assertThat(secondResult.getReservedQtySum().get().isZero()).isTrue();
	}

	private void assertThatHuHasQty(final I_M_HU hu, final String expectedQty)
	{
		final Quantity expectedQuantity = Quantity.of(new BigDecimal(expectedQty), cuUOM);
		assertThat(hu).hasStorage(data.helper.pTomatoProductId, expectedQuantity);
//		final BigDecimal luQuantity = extractQty(hu);
//		assertThat(luQuantity).isEqualByComparingTo(expectedQty);
	}

	private Condition<I_M_HU> hasQty(final String qty)
	{
		return new Condition<>(hu -> extractQty(hu).compareTo(new BigDecimal(qty)) == 0, "hu has qty=%s", qty);
	}

	private BigDecimal extractQty(final I_M_HU hu)
	{
		final IHUStorage storage = handlingUnitsBL.getStorageFactory().getStorage(hu);
		final List<IHUProductStorage> productStorages = storage.getProductStorages();
		assertThat(productStorages).hasSize(1);

		final Quantity luQuantity = productStorages.get(0).getQty(cuUOM);
		return luQuantity.getAsBigDecimal();
	}

	@Test
	public void retainAvailableHUsForOrderLine()
	{
		final HuId huId10 = HuId.ofRepoId(10);
		final HuId huId11 = HuId.ofRepoId(11);
		final HuId huId20 = HuId.ofRepoId(20);
		final HuId huId21 = HuId.ofRepoId(21);

		final HuReservation huReservation = HuReservation.builder()
				.salesOrderLineId(OrderLineId.ofRepoId(20))
				.vhuId2reservedQty(huId10, Quantity.of(TEN, cuUOM))
				.vhuId2reservedQty(huId11, Quantity.of(ONE, cuUOM))
				.reservedQtySum(Optional.of(Quantity.of(ELEVEN, cuUOM)))
				.build();
		huReservationRepository.save(huReservation);

		final HuReservation huReservation2 = HuReservation.builder()
				.salesOrderLineId(OrderLineId.ofRepoId(30))
				.vhuId2reservedQty(huId20, Quantity.of(TEN, cuUOM))
				.vhuId2reservedQty(huId21, Quantity.of(ONE, cuUOM))
				.reservedQtySum(Optional.of(Quantity.of(ELEVEN, cuUOM)))
				.build();
		huReservationRepository.save(huReservation2);

		//huReservationService.retainAvailableHUsForOrderLine(huIds, orderLineId)
	}
}
