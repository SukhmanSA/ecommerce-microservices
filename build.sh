echo "Building Ecommerce Microservices..."

build_service() {
    SERVICE_NAME=$1
    echo "Building $SERVICE_NAME..."
    cd $SERVICE_NAME || exit

    if [ -d "target" ]; then
        echo "Deleting target folder for $SERVICE_NAME..."
        rm -rf target
    fi

    mvnd clean package -DskipTests
    cd ..
}

# build_service "DiscoveryService"
build_service "ProductService"
build_service "CartService"
build_service "OrderService"
# build_service "ApiGateway"

echo "All services built successfully!"

# docker build -t sukhman78/ecommerce-repo:ecommerce-order-service .
# docker push sukhman78/ecommerce-repo:ecommerce-user-service 