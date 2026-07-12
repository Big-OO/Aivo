package com.assistant.aivo.presentation.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assistant.aivo.presentation.ui.Product

@Composable
fun ProductsCarousel(
    products: List<Product>,
    favoriteIds: Set<Long>,
    onProductClick: (Long) -> Unit,
    onFavoriteClick: (Product) -> Unit,
    onAddToCartClick: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    ) {
        items(products, key = { it.id }) { product ->
            ProductChatCardWrapper(
                product = product,
                isFavorite = favoriteIds.contains(product.id),
                onClick = { onProductClick(product.id) },
                onFavoriteClick = { onFavoriteClick(product) },
                onAddToCartClick = { onAddToCartClick(product) }
            )
        }
    }
}

@Composable
fun ProductChatCardWrapper(
    product: Product,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onAddToCartClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.width(168.dp)) {
        ProductCard(
            name = product.title,
            price = product.price,
            imageUrl = product.imageUrl,
            compareAtPrice = null,
            isOnSale = false,
            isFavorite = isFavorite,
            onClick = onClick,
            onFavoriteClick = onFavoriteClick
        )
        // Cart overlay chip
        FilledIconButton(
            onClick = onAddToCartClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(30.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.Default.ShoppingCart,
                contentDescription = "Add to Cart",
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
