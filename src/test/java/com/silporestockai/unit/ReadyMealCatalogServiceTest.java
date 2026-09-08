package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.CatalogCandidate;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.service.CartBuildingService;
import com.silporestockai.service.ReadyMealCatalogService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReadyMealCatalogServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final CartContext CONTEXT = new CartContext(
            "cart-1", "branch-7", "company-3", "delivery", "2026-09-07T10:00:00Z", "2026-09-07T12:00:00Z");

    private SilpoMcpClient silpoMcpClient;
    private CartBuildingService cartBuildingService;
    private ReadyMealCatalogService service;

    private void setUp() {
        silpoMcpClient = mock(SilpoMcpClient.class);
        cartBuildingService = mock(CartBuildingService.class);
        when(cartBuildingService.getOrCreateCartContext(USER_ID)).thenReturn(CONTEXT);
        when(cartBuildingService.firstDeliverableSlot(USER_ID, CONTEXT))
                .thenReturn(new OfferedSlot("slot-1", "2026-09-07T10:00:00Z", null, "2026-09-07T12:00:00Z"));
        service = new ReadyMealCatalogService(silpoMcpClient, cartBuildingService);
    }

    @Test
    void resolvesCartContextThenSearchesTheFixedCategoryTermsInOneCall() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("""
                        {"queries":[{"query":"салат готовий","products":[\
                        {"name":"Салат Цезар готовий","productId":"p-1","companyId":"company-3","branchId":"branch-7","price":89.9}]}]}""", null, false));

        List<CatalogCandidate> candidates = service.findCandidates(USER_ID);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().name()).isEqualTo("Салат Цезар готовий");
        assertThat(candidates.getFirst().productId()).isEqualTo("p-1");
        assertThat(candidates.getFirst().price()).isEqualByComparingTo("89.9");
        verify(cartBuildingService).getOrCreateCartContext(USER_ID);
        verify(cartBuildingService).firstDeliverableSlot(USER_ID, CONTEXT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(silpoMcpClient).callTool(eq("silpo_find_products_batch"), argsCaptor.capture(), eq(USER_ID));
        assertThat(argsCaptor.getValue().get("branchId")).isEqualTo("branch-7");
        assertThat(argsCaptor.getValue().get("products"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isNotEmpty();
    }

    /**
     * Live on 2026-09-08 a ready-meals cart died on Silpo's stock validation: five candidates were deli products
     * sold by weight (smoked fish, salads per kilogram), the plan said «1 порція», the pre-resolved line carried
     * no weight, and the cart sent quantity 1 — a kilogram against 0.4 in stock. A portion of a by-weight product
     * is not a thing this planner can order, and neither is a product with nothing on the shelf.
     */
    @Test
    void leavesOutProductsSoldByWeightAndProductsWithNothingInStock() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("""
                        {"queries":[{"query":"салат готовий","products":[\
                        {"name":"Салат Цезар готовий","productId":"p-1","price":89.9,"stock":12},\
                        {"name":"Салат «Грецький»","productId":"p-2","price":399,"weighted":true,"stock":0.6},\
                        {"name":"Плов з куркою","productId":"p-3","price":120,"stock":0},\
                        {"name":"Банош з бринзою","productId":"p-4","price":95,"available":false}]}]}""", null, false));

        List<CatalogCandidate> candidates = service.findCandidates(USER_ID);

        assertThat(candidates).extracting(CatalogCandidate::productId).containsExactly("p-1");
    }

    @Test
    void flattensEveryProductAcrossEveryQueryNotJustTheFirstMatch() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("""
                        {"queries":[\
                        {"query":"салат готовий","products":[\
                        {"name":"Салат Цезар готовий","productId":"p-1"},\
                        {"name":"Салат Грецький готовий","productId":"p-2"}]},\
                        {"query":"борщ готовий","products":[{"name":"Борщ готовий, порція","productId":"p-3"}]}]}""", null, false));

        List<CatalogCandidate> candidates = service.findCandidates(USER_ID);

        assertThat(candidates).extracting(CatalogCandidate::productId).containsExactlyInAnyOrder("p-1", "p-2", "p-3");
    }

    @Test
    void dedupesTheSameProductIdReturnedByTwoDifferentSearchTerms() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("""
                        {"queries":[\
                        {"query":"готові страви","products":[{"name":"Плов з куркою","productId":"p-9"}]},\
                        {"query":"плов готовий","products":[{"name":"Плов з куркою","productId":"p-9"}]}]}""", null, false));

        List<CatalogCandidate> candidates = service.findCandidates(USER_ID);

        assertThat(candidates).hasSize(1);
    }

    @Test
    void returnsAnEmptyListRatherThanFailingWhenTheCatalogHasNothing() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("{\"queries\":[]}", null, false));

        assertThat(service.findCandidates(USER_ID)).isEmpty();
    }

    @Test
    void throwsWhenTheToolReportsAnError() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse(null, null, true));

        assertThatThrownBy(() -> service.findCandidates(USER_ID)).isInstanceOf(CartBuildException.class);
    }
}
