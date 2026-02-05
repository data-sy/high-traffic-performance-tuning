package com.project.service.product;

import com.project.api.product.dto.ProductListResponse;
import com.project.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public Page<ProductListResponse> getProducts(String category, Pageable pageable) {
        return productRepository.findByCategoryOrderByCreatedAtDesc(category, pageable)
                .map(ProductListResponse::from);
    }
}
