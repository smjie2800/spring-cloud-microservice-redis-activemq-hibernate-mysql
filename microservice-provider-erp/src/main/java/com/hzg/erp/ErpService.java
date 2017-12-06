package com.hzg.erp;

import com.google.common.reflect.TypeToken;
import com.hzg.pay.Account;
import com.hzg.pay.Pay;
import com.hzg.pay.Refund;
import com.hzg.sys.*;
import com.hzg.tools.*;
import com.sf.openapi.common.entity.AppInfo;
import com.sf.openapi.common.entity.HeadMessageReq;
import com.sf.openapi.common.entity.MessageReq;
import com.sf.openapi.common.entity.MessageResp;
import com.sf.openapi.express.sample.order.dto.*;
import com.sf.openapi.express.sample.order.tools.OrderTools;
import com.sf.openapi.express.sample.security.dto.TokenReqDto;
import com.sf.openapi.express.sample.security.dto.TokenRespDto;
import com.sf.openapi.express.sample.security.tools.SecurityTools;
import com.sf.openapi.express.sample.waybill.dto.WaybillReqDto;
import com.sf.openapi.express.sample.waybill.dto.WaybillRespDto;
import com.sf.openapi.express.sample.waybill.tools.WaybillDownloadTools;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.log4j.Logger;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.*;

@Service
public class ErpService {

    Logger logger = Logger.getLogger(ErpService.class);

    @Autowired
    private ErpDao erpDao;

    @Autowired
    private SysClient sysClient;

    @Autowired
    private PayClient payClient;

    @Autowired
    private Writer writer;

    @Autowired
    public ObjectToSql objectToSql;

    @Autowired
    public SessionFactory sessionFactory;

    @Autowired
    private DateUtil dateUtil;

    @Autowired
    BarcodeUtil barcodeUtil;

    @Autowired
    ImageBase64 imageBase64;

    @Autowired
    private HttpProxyDiscovery httpProxyDiscovery;

    @Autowired
    private SfExpress sfExpress;

    public String launchAuditFlow(String entity, Integer entityId, String entityNo, String auditName, String content, User user) {
        String result = CommonConstant.fail;

        logger.info("launchAuditFlow start:" + result);

        /**
         * 创建审核流程第一个节点，发起审核流程
         */
        Audit audit = new Audit();
        audit.setEntity(entity);
        audit.setEntityId(entityId);
        audit.setEntityNo(entityNo);
        audit.setName(auditName);
        audit.setContent(content);

        Post post = (Post)(((List<User>)erpDao.query(user)).get(0)).getPosts().toArray()[0];
        audit.setCompany(post.getDept().getCompany());

        Map<String, String> result1 = writer.gson.fromJson(sysClient.launchAuditFlow(writer.gson.toJson(audit)),
                new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());

        result = result1.get(CommonConstant.result);

        logger.info("launchAuditFlow end, result:" + result);

        return result;
    }

    public String launchAuditFlowByPost(String entity, Integer entityId, String entityNo, String auditName, Post post, String preFlowAuditNo) {
        String result = CommonConstant.fail;

        logger.info("launchAuditFlowByPost start:" + result);

        /**
         * 创建审核流程第一个节点，发起审核流程
         */
        Audit audit = new Audit();
        audit.setEntity(entity);
        audit.setEntityId(entityId);
        audit.setEntityNo(entityNo);
        audit.setName(auditName);
        audit.setPreFlowAuditNo(preFlowAuditNo);

        audit.setPost(post);
        audit.setCompany(post.getDept().getCompany());

        Map<String, String> result1 = writer.gson.fromJson(sysClient.launchAuditFlow(writer.gson.toJson(audit)),
                new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());

        result = result1.get(CommonConstant.result);

        logger.info("launchAuditFlowByPost end, audit result:" + result);

        return result;
    }

    /**
     * 查询同批次同类型商品是否已经发起审核流程
     * @param product
     * @return
     */
    public List queryProductAudit(Product product) {
        List audits = null;

        try {
            Audit audit = new Audit();
            audit.setEntity(AuditFlowConstant.business_product);
            audit.setEntityNo(product.getNo());

            String auditSql = objectToSql.generateSelectSqlByAnnotation(audit);
            String selectSql = "", fromSql = "", whereSql = "", sortNumSql = "";

            String[] sqlParts = erpDao.getSqlPart(auditSql, Audit.class);
            selectSql = sqlParts[0];
            fromSql = sqlParts[1];
            whereSql = sqlParts[2];
            sortNumSql = sqlParts[3];

            fromSql += ", " + objectToSql.getTableName(Product.class) + " t21 ";
            if (!whereSql.trim().equals("")) {
                whereSql += " and ";
            }
            whereSql += " t21." + objectToSql.getColumn(Product.class.getDeclaredField("no")) +
                    " = t." + objectToSql.getColumn(Audit.class.getDeclaredField("entityNo")) +
                    " and t21." + objectToSql.getColumn(Product.class.getDeclaredField("describe")) +
                    " = " + product.getDescribe().getId();

            audits = erpDao.queryBySql("select " + selectSql + " from " + fromSql + " where " + whereSql + " order by " + sortNumSql, Audit.class);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return audits;
    }

    public String queryProductOnSalePreFlowAuditNo(Product product){
        PurchaseDetailProduct detailProduct = new PurchaseDetailProduct();
        detailProduct.setProduct(product);
        PurchaseDetail detail = ((PurchaseDetailProduct)erpDao.query(detailProduct).get(0)).getPurchaseDetail();

        Audit audit = new Audit();
        audit.setEntity(Purchase.class.getSimpleName().toLowerCase());
        audit.setEntityId(detail.getPurchase().getId());

        List<Audit> dbAudits = writer.gson.fromJson(
                sysClient.query(Audit.class.getSimpleName().toLowerCase(), writer.gson.toJson(audit)),
                new com.google.gson.reflect.TypeToken<List<Audit>>() {}.getType());

        return dbAudits.get(0).getNo();
    }

    public String updateAudit(Integer entityId, String oldEntity, String newEntity, String newName, String newContent) {
        String result = CommonConstant.fail;

        Audit audit = new Audit();
        audit.setEntity(oldEntity);
        audit.setEntityId(entityId);

        List<Audit> dbAudits = writer.gson.fromJson(
                sysClient.query(Audit.class.getSimpleName().toLowerCase(), writer.gson.toJson(audit)),
                new com.google.gson.reflect.TypeToken<List<Audit>>() {}.getType());

        for (Audit audit1 : dbAudits) {
            audit.setId(audit1.getId());
            audit.setName(newName);
            audit.setContent(newContent);
            audit.setEntity(newEntity);

            Map<String, String> result1 = writer.gson.fromJson(sysClient.update(Audit.class.getSimpleName().toLowerCase(), writer.gson.toJson(audit)),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());

            result = result1.get(CommonConstant.result);
        }

        return result;
    }

    public String deleteAudit(Integer entityId, String entity) {
        String result = CommonConstant.fail;

        Audit audit = new Audit();
        audit.setEntity(entity);
        audit.setEntityId(entityId);

        List<Audit> dbAudits = writer.gson.fromJson(
                sysClient.query(Audit.class.getSimpleName().toLowerCase(), writer.gson.toJson(audit)),
                new com.google.gson.reflect.TypeToken<List<Audit>>() {}.getType());

        for (Audit audit1 : dbAudits) {
            audit.setId(audit1.getId());

            Map<String, String> result1 = writer.gson.fromJson(sysClient.delete(Audit.class.getSimpleName().toLowerCase(), writer.gson.toJson(audit)),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());

            result = result1.get(CommonConstant.result);
        }

        return result;
    }

    public String savePurchaseProducts(Purchase purchase) {
        String result = CommonConstant.fail;

        if (purchase.getDetails() != null) {
            for (PurchaseDetail detail : purchase.getDetails()) {
                Product product = detail.getProduct();

                detail.setProduct(product);
                detail.setProductNo(product.getNo());
                detail.setProductName(product.getName());
                detail.setAmount(product.getUnitPrice() * detail.getQuantity());
                detail.setPrice(product.getUnitPrice());

                Purchase doubleRelatePurchase = new Purchase();
                doubleRelatePurchase.setId(purchase.getId());
                detail.setPurchase(doubleRelatePurchase);
                result += erpDao.save(detail);

                erpDao.deleteFromRedis(detail.getClass().getName() + CommonConstant.underline + detail.getId());

                PurchaseDetailProduct detailProduct = new PurchaseDetailProduct();
                detailProduct.setPurchaseDetail(detail);

                /**
                 * 采购了多少数量的商品，就插入多少数量的商品记录
                 */
                int productQuantity = detail.getQuantity().intValue();
                if (detail.getUnit().equals(ErpConstant.unit_g) || detail.getUnit().equals(ErpConstant.unit_kg) ||
                    detail.getUnit().equals(ErpConstant.unit_ct) || detail.getUnit().equals(ErpConstant.unit_oz)) {
                    productQuantity = 1;
                }

                ProductDescribe describe = product.getDescribe();
                result += erpDao.save(describe);

                for (int i = 0; i < productQuantity; i++) {
                    product.setDescribe(describe);
                    result += erpDao.insert(product);

                    /**
                     * 因为保存完 product 才保存它对应的 properties，所以会导致 product 里的 properties，是前一个 product 的 properties
                     * 所以需要删除 redis 里的 product
                     */
                    erpDao.deleteFromRedis(product.getClass().getName() + CommonConstant.underline + product.getId());

                    /**
                     * 使用 new 新建，避免直接使用已经包含 property 属性的 product， 使得 product 与 property 循环嵌套
                     */
                    Product doubleRelateProduct = new Product();
                    doubleRelateProduct.setId(product.getId());

                    if (product.getProperties() != null) {
                        for (ProductOwnProperty ownProperty : product.getProperties()) {
                            ownProperty.setProduct(doubleRelateProduct);
                            result += erpDao.insert(ownProperty);
                        }
                    }

                    detailProduct.setProduct(product);
                    result += erpDao.insert(detailProduct);
                }
            }
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public String deletePurchaseProducts(Purchase purchase) {
        String result = CommonConstant.fail;

        if (purchase.getDetails() != null) {
            for (PurchaseDetail detail : purchase.getDetails()) {
                PurchaseDetail dbDetail = (PurchaseDetail) erpDao.queryById(detail.getId(), detail.getClass());

                Product product = null;
                for (PurchaseDetailProduct detailProduct : dbDetail.getPurchaseDetailProducts()) {
                    product = (Product) erpDao.queryById(detailProduct.getProduct().getId(), detailProduct.getProduct().getClass());

                    if (product.getProperties() != null && !product.getProperties().isEmpty()) {
                        for (ProductOwnProperty ownProperty : product.getProperties()) {
                            result += erpDao.delete(ownProperty);
                        }
                    }

                    result += erpDao.delete(product);
                }

                if (product != null) {
                    result += erpDao.delete(product.getDescribe());
                }

                result += erpDao.delete(detail);
            }
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    /**
     * 设置入库
     * @param stockIn
     * @return
     */
    public String stockIn(StockInOut stockIn){
        String result = CommonConstant.fail;

        result += isCanStockIn(stockIn);
        if (!result.contains(CommonConstant.fail+CommonConstant.fail)) {
            StockInOut stateStockIn = new StockInOut();
            stateStockIn.setId(stockIn.getId());
            stateStockIn.setState(stockIn.getState());
            result += erpDao.updateById(stateStockIn.getId(), stateStockIn);

            result += setStockProductIn(stockIn);

            /**
             * 押金入库后通知仓储预计退还货物时间，财务人员预计退还押金时间
             */
            if (stockIn.getType().compareTo(ErpConstant.stockInOut_type_deposit) == 0) {
                result += launchAuditFlow(AuditFlowConstant.business_stockIn_deposit_cangchu, stockIn.getId(), stockIn.getNo(),
                        "押金入库单 " + stockIn.getNo() + ", 预计" + stockIn.getDeposit().getReturnGoodsDate() + "退货",
                        "请注意退货时间：" + stockIn.getDeposit().getReturnGoodsDate(),
                        stockIn.getInputer());

                result += launchAuditFlow(AuditFlowConstant.business_stockIn_deposit_caiwu, stockIn.getId(), stockIn.getNo(),
                        "押金入库单 " + stockIn.getNo() + ", 预计" + stockIn.getDeposit().getReturnDepositDate() + "退押金",
                        "请注意退押金时间：" + stockIn.getDeposit().getReturnDepositDate(),
                        stockIn.getInputer());
            }
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    /**
     * 设置入库库存，商品入库状态
     * @param stockIn
     * @return
     */
    public String setStockProductIn(StockInOut stockIn) {
        String result = CommonConstant.fail;

        for (StockInOutDetail detail : stockIn.getDetails()) {
            result += backupPreStockInOut(((StockInOutDetailProduct)detail.getStockInOutDetailProducts().toArray()[0]).getProduct(), stockIn.getId());

            /**
             * 修改商品为入库
             */
            for (StockInOutDetailProduct detailProduct : detail.getStockInOutDetailProducts()) {
                detailProduct.getProduct().setState(ErpConstant.product_state_stockIn);
                result += erpDao.updateById(detailProduct.getProduct().getId(), detailProduct.getProduct());
            }

            Product dbProduct = (Product) erpDao.queryById(((StockInOutDetailProduct)(detail.getStockInOutDetailProducts().toArray()[0])).getProduct().getId(), Product.class);

            /**
             * 调仓入库，设置调仓出库为完成状态
             */
            if (stockIn.getType().compareTo(ErpConstant.stockInOut_type_changeWarehouse) == 0) {
                StockInOut stockOutChangeWarehouse = getLastStockInOutByProductAndType(dbProduct, ErpConstant.stockOut);
                stockOutChangeWarehouse.getChangeWarehouse().setState(ErpConstant.stockInOut_state_changeWarehouse_finished);
                result += erpDao.updateById(stockOutChangeWarehouse.getChangeWarehouse().getId(), stockOutChangeWarehouse.getChangeWarehouse());
            }

            /**
             * 添加库存
             */
            Stock tempStock = new Stock();
            tempStock.setProductNo(dbProduct.getNo());
            tempStock.setWarehouse(stockIn.getWarehouse());

            /**
             * 在同一个仓库的同类商品做增量入库，才修改商品数量
             */
            if (stockIn.getType().compareTo(ErpConstant.stockInOut_type_increment) == 0) {
                List<Stock> dbStocks = erpDao.query(tempStock);

                if (!dbStocks.isEmpty()) {
                    dbStocks.get(0).setDate(stockIn.getDate());
                    result += setStockQuantity(dbStocks.get(0), detail.getQuantity(), CommonConstant.add);

                } else {
                    result += saveStock(tempStock, detail.getQuantity(), detail.getUnit(), stockIn.getDate());
                }

            } else {
                result += saveStock(tempStock, detail.getQuantity(), detail.getUnit(), stockIn.getDate());
            }

            /**
             * 在 redis 里，使用商品编号关联库存在 redis 里的 key，以便后期快速查询该编号商品库存
             */
            erpDao.putKeyToHash(ErpConstant.stock + CommonConstant.underline + tempStock.getProductNo(),
                    tempStock.getClass().getName() + CommonConstant.underline + tempStock.getId());
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }


    /**
     * 出库
     * @param stockOut
     * @return
     */
    public String stockOut(StockInOut stockOut){
        String result = CommonConstant.fail;

        result += isCanStockOut(stockOut);
        if (!result.contains(CommonConstant.fail+CommonConstant.fail)) {
            StockInOut stateStockOut = new StockInOut();
            stateStockOut.setId(stockOut.getId());
            stateStockOut.setState(stockOut.getState());
            result += erpDao.updateById(stateStockOut.getId(), stateStockOut);

            if (stockOut.getType().compareTo(ErpConstant.stockInOut_type_normal_outWarehouse) == 0) {
                /**
                 * 确认订单支付完成后，系统会自动出库商品，即系统自动出库，这时没有出库人员，因此随机设置出库人员
                 */
                stockOut.setInputer(getRandomStockOutUser());

                /**
                 * 设置出库库存,商品出库状态, 提醒出库人员打印快递单
                 */
                result += setStockProductOut(stockOut);
                result += launchAuditFlow(AuditFlowConstant.business_stockOut_print_expressWaybill_notify, stockOut.getId(), stockOut.getNo(),
                        "打印出库单:" + stockOut.getNo() + " 里商品的快递单", "打印出库单:" + stockOut.getNo() + " 里商品的快递单",
                        stockOut.getInputer());


            } else if (stockOut.getType().compareTo(ErpConstant.stockInOut_type_breakage_outWarehouse) == 0) {
                /**
                 * 报损出库进入报损出库审批流程
                 */
                result += launchAuditFlow(AuditFlowConstant.business_stockOut_breakage, stockOut.getId(), stockOut.getNo(),
                        "出库单:" + stockOut.getNo() + " 商品报损出库", "请报损出库出库单" + stockOut.getNo() + " 的商品",
                        stockOut.getInputer());


            } else {
                /**
                 * 设置出库库存,商品出库状态
                 */
                result += setStockProductOut(stockOut);
            }
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    /**
     * 设置出库库存，商品出库状态
     * @param stockOut
     * @return
     */
    public String setStockProductOut(StockInOut stockOut) {
        String result = CommonConstant.fail;

        for (StockInOutDetail detail : stockOut.getDetails()) {
            result += backupPreStockInOut(((StockInOutDetailProduct)detail.getStockInOutDetailProducts().toArray()[0]).getProduct(), stockOut.getId());

            for (StockInOutDetailProduct detailProduct : detail.getStockInOutDetailProducts()) {
                detailProduct.getProduct().setState(ErpConstant.product_state_stockOut);
                result += erpDao.updateById(detailProduct.getProduct().getId(), detailProduct.getProduct());
            }

            Product dbProduct = (Product) erpDao.queryById(((StockInOutDetailProduct)(detail.getStockInOutDetailProducts().toArray()[0])).getProduct().getId(), Product.class);

            Stock tempStock = new Stock();
            tempStock.setProductNo(dbProduct.getNo());
            tempStock.setWarehouse(getLastStockInOutByProductAndType(dbProduct, ErpConstant.stockIn).getWarehouse());
            Stock dbStock = (Stock)erpDao.query(tempStock).get(0);

            if (dbStock.getQuantity().compareTo(detail.getQuantity()) >= 0) {
                result += setStockQuantity(dbStock, detail.getQuantity(), CommonConstant.subtract);

                /**
                 * 库存数量为 0，则删除库存
                 */
                if (dbStock.getQuantity().compareTo(0f) == 0) {
                    result += erpDao.delete(dbStock);
                    erpDao.deleteFromRedis(dbStock.getClass().getName() + CommonConstant.underline + dbStock.getId());
                }

            } else {
                result += CommonConstant.fail + ",商品：" + dbStock.getProductNo() + " 库存数量不足，不能出库";
            }

        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    /**
     * 设置之前的入库出库完成记录为归档状态
     * @param product
     * @param currentStockInOutId
     * @return
     */
    public String backupPreStockInOut(Product product, Integer currentStockInOutId){
        String result = "";

        StockInOutDetailProduct queryDetailProduct = new StockInOutDetailProduct();
        queryDetailProduct.setProduct(product);
        List<StockInOutDetailProduct> StockInOutDetailProduct = erpDao.query(queryDetailProduct);

        for (StockInOutDetailProduct dbDetailProduct : StockInOutDetailProduct) {
            StockInOutDetail dbDetail = (StockInOutDetail) erpDao.queryById(dbDetailProduct.getStockInOutDetail().getId(), dbDetailProduct.getStockInOutDetail().getClass());

            if (dbDetail.getStockInOut().getState().compareTo(ErpConstant.stockInOut_state_finished) == 0 &&
                    dbDetail.getStockInOut().getId().compareTo(currentStockInOutId) != 0) {
                dbDetail.getStockInOut().setState(ErpConstant.stockInOut_state_backup);
                result += erpDao.updateById(dbDetail.getStockInOut().getId(), dbDetail.getStockInOut());
            }
        }

        return result;
    }

    public String saveStock(Stock stock, Float quantity, String unit, Timestamp date) {
        stock.setNo(erpDao.getNo(ErpConstant.no_stock_perfix));
        stock.setState(ErpConstant.stock_state_valid);
        stock.setQuantity(quantity);
        stock.setUnit(unit);
        stock.setDate(date);

        return  erpDao.save(stock);
    }

    /**
     * 根据商品获取出库/入库
     */
    public StockInOut getLastStockInOutByProductAndType(Product product, String type) {
        StockInOutDetailProduct detailProduct = new StockInOutDetailProduct();
        detailProduct.setProduct(product);
        List<StockInOutDetailProduct> detailProducts = erpDao.query(detailProduct);

        List<StockInOut> stockInOuts = new ArrayList<>();
        for (StockInOutDetailProduct ele : detailProducts) {
            stockInOuts.add((StockInOut) erpDao.queryById(ele.getStockInOutDetail().getStockInOut().getId(), ele.getStockInOutDetail().getStockInOut().getClass()));
        }

        StockInOut[] stockInOutsArr = new StockInOut[stockInOuts.size()];
        stockInOuts.toArray(stockInOutsArr);

        Arrays.sort(stockInOutsArr, new Comparator<StockInOut>() {
            @Override
            public int compare(StockInOut o1, StockInOut o2) {
                if (o1.getId().compareTo(o2.getId()) > 0) {
                    return 1;
                } else if (o1.getId().compareTo(o2.getId()) < 0) {
                    return -1;
                }

                return 0;
            }
        });

        for (int i = 0; i < stockInOutsArr.length; i++) {

            if (type.equals(ErpConstant.stockIn)) {
                if (stockInOutsArr[i].getType().compareTo(ErpConstant.stockInOut_type_virtual_outWarehouse) < 0) {
                    return stockInOutsArr[i];
                }

            } else if (type.equals(ErpConstant.stockOut)) {
                if (stockInOutsArr[i].getType().compareTo(ErpConstant.stockInOut_type_virtual_outWarehouse) >= 0) {
                    return stockInOutsArr[i];
                }
            }
        }

        return null;
    }

    public String saveStockInOut(StockInOut stockInOut) {
        String result = CommonConstant.fail;

        if (stockInOut.getType().compareTo(ErpConstant.stockInOut_type_deposit) == 0) {
            result += erpDao.save(stockInOut.getDeposit());
        }

        if (stockInOut.getType().compareTo(ErpConstant.stockInOut_type_process) == 0 ||
                stockInOut.getType().compareTo(ErpConstant.stockInOut_type_repair) == 0) {
            result += erpDao.save(stockInOut.getProcessRepair());
        }

        if (stockInOut.getType().compareTo(ErpConstant.stockInOut_type_changeWarehouse_outWarehouse) == 0) {
            stockInOut.getChangeWarehouse().setState(ErpConstant.stockInOut_state_changeWarehouse_unfinished);
            result += erpDao.save(stockInOut.getChangeWarehouse());
        }

        result += erpDao.save(stockInOut);
        result += saveStockInOutDetails(stockInOut);

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public String saveStockInOutDetails(StockInOut stockInOut) {
        String result = CommonConstant.fail;

        for (StockInOutDetail detail : stockInOut.getDetails()) {
            detail.setStockInOut(stockInOut);
            result += erpDao.save(detail);

            for (StockInOutDetailProduct detailProduct : detail.getStockInOutDetailProducts()) {
                /**
                 * 由于 insert 方法里调用了 writer.gson.fromJson 方法，如果类和子类有嵌套，就会一直重复解析子类对象，
                 * 因此new一个对象来防止嵌套
                 */
                StockInOutDetail idDetail = new StockInOutDetail();
                idDetail.setId(detail.getId());
                detailProduct.setStockInOutDetail(idDetail);
                result += erpDao.insert(detailProduct);
            }
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    private String setStockQuantity(Stock stock, Float quantity, String operator) {
        BigDecimal dbQuantity = new BigDecimal(Float.toString(stock.getQuantity()));
        BigDecimal addQuantity = new BigDecimal(Float.toString(quantity));

        if (operator.equals(CommonConstant.add)) {
            stock.setQuantity(dbQuantity.add(addQuantity).floatValue());
        } else if (operator.equals(CommonConstant.subtract)) {
            stock.setQuantity(dbQuantity.subtract(addQuantity).floatValue());
        }

        return erpDao.updateById(stock.getId(), stock);
    }

    public String purchaseStateModify(Audit audit, Integer purchaseState, Integer productState) {
        String result = CommonConstant.fail;

        Purchase purchase = (Purchase)erpDao.queryById(audit.getEntityId(), Purchase.class);

        Purchase statePurchase = new Purchase();
        statePurchase.setId(purchase.getId());
        statePurchase.setState(purchaseState);

        result += erpDao.updateById(statePurchase.getId(), statePurchase);
        if (result.contains(CommonConstant.success)) {
            for (PurchaseDetail detail : purchase.getDetails()) {
                result += setProductStateByPurchaseDetail(detail, productState);
            }
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public String purchaseProductsStateModify(Audit audit, Integer productState) {
        String result = CommonConstant.fail;

        Purchase purchase = (Purchase)erpDao.queryById(audit.getEntityId(), Purchase.class);
        for (PurchaseDetail detail : purchase.getDetails()) {
            result += setProductStateByPurchaseDetail(detail, productState);
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public String setProductStateByPurchaseDetail(PurchaseDetail detail, Integer productState) {
        String result = CommonConstant.fail;

        PurchaseDetailProduct detailProduct = new PurchaseDetailProduct();
        detailProduct.setPurchaseDetail(detail);
        List<PurchaseDetailProduct> dbDetailProducts = erpDao.query(detailProduct);

        for (PurchaseDetailProduct dbDetailProduct : dbDetailProducts) {
            Product stateProduct = new Product();
            stateProduct.setId(dbDetailProduct.getProduct().getId());
            stateProduct.setState(productState);
            result += erpDao.updateById(stateProduct.getId(), stateProduct);
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }


    public String purchaseEmergencyPass(Audit audit, Integer purchaseState, Integer productState) {
        String result = CommonConstant.fail;

        result += purchaseStateModify(audit, purchaseState, productState);

        if (result.contains(CommonConstant.success)) {
            Purchase purchase = (Purchase) erpDao.queryById(audit.getEntityId(), Purchase.class);

            Pay pay = new Pay();
            pay.setAmount(-purchase.getAmount());
            pay.setState(PayConstants.state_pay_apply);

            pay.setPayAccount(purchase.getAccount().getAccount());
            pay.setPayBranch(purchase.getAccount().getBranch());
            pay.setPayBank(purchase.getAccount().getBank());

            pay.setEntity(Purchase.class.getSimpleName().toLowerCase());
            pay.setEntityId(purchase.getId());
            pay.setEntityNo(purchase.getNo());

            PurchaseDetail detail = null;
            for (PurchaseDetail ele : purchase.getDetails()) {
                detail = ele;
                break;
            }

            if (detail != null) {
                detail = (PurchaseDetail) erpDao.queryById(detail.getId(), PurchaseDetail.class);

                pay.setReceiptAccount(detail.getSupplier().getAccount());
                pay.setReceiptBranch(detail.getSupplier().getBranch());
                pay.setReceiptBank(detail.getSupplier().getBank());
            }

            Map<String, String> result1 = writer.gson.fromJson(payClient.save(pay.getClass().getSimpleName(), writer.gson.toJson(pay)),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
            result += result1.get(CommonConstant.result);
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public String purchaseEmergencyPay(Audit audit) {

        Purchase purchase = (Purchase) erpDao.queryById(audit.getEntityId(), Purchase.class);
        return setPayAccountInfo(getPaysByEntity(purchase.getClass().getSimpleName().toLowerCase(), purchase.getId()), purchase.getAccount(),
                PayConstants.state_pay_success, CommonConstant.add);
    }

    public String stockInReturnDeposit(Audit audit) {
        String result = CommonConstant.fail;

        StockInOut stockInOut = (StockInOut) erpDao.queryById(audit.getEntityId(), StockInOut.class);
        Purchase purchase = (Purchase) erpDao.queryById(stockInOut.getDeposit().getPurchase().getId(), stockInOut.getDeposit().getPurchase().getClass());
        Pay pay = getPaysByEntity(purchase.getClass().getSimpleName().toLowerCase(), purchase.getId());

        List<Account> accounts = writer.gson.fromJson(payClient.query(purchase.getAccount().getClass().getSimpleName(), writer.gson.toJson(purchase.getAccount())),
                new TypeToken<List<Account>>(){}.getType());
        result += setPayAccountInfo(pay, accounts.get(0), null, CommonConstant.subtract);

        Refund refund = new Refund();
        refund.setPay(pay);
        refund.setState(PayConstants.state_refund_apply);
        refund.setPayBank(pay.getPayBank());
        refund.setRefundDate(stockInOut.getDeposit().getReturnDepositDate());
        refund.setInputDate(dateUtil.getSecondCurrentTimestamp());
        refund.setEntity(ErpConstant.return_purchase_deposit);
        refund.setEntityId(purchase.getId());

        Map<String, String> result1 = writer.gson.fromJson(payClient.save(refund.getClass().getSimpleName(), writer.gson.toJson(refund)),
                new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
        result += result1.get(CommonConstant.result);

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public String setPayAccountInfo(Pay pay, Account account, Integer payState, String operator) {
        String result = CommonConstant.fail;

        pay.setState(payState);
        pay.setPayDate(dateUtil.getSecondCurrentTimestamp());

        Map<String, String> result1 = writer.gson.fromJson(payClient.update(Pay.class.getSimpleName(), writer.gson.toJson(pay)),
                new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
        result += result1.get(CommonConstant.result);

        if (result.contains(CommonConstant.success)) {
            /**
             * 使用 BigDecimal 进行精度计算
             */
            BigDecimal accountAmount = new BigDecimal(Float.toString(account.getAmount()));
            BigDecimal payAmount = new BigDecimal(Float.toString(pay.getAmount()));

            if (operator.equals(CommonConstant.add)) {
                account.setAmount(accountAmount.add(payAmount).floatValue());
            } else if (operator.equals(CommonConstant.subtract)) {
                account.setAmount(accountAmount.subtract(payAmount).floatValue());
            }

            result1 = writer.gson.fromJson(payClient.update(Account.class.getSimpleName(), writer.gson.toJson(account)),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
            result += result1.get(CommonConstant.result);
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public Pay getPaysByEntity(String entity, Integer entityId) {
        Pay pay = new Pay();
        pay.setEntity(entity);
        pay.setEntityId(entityId);

        List<Pay> pays = writer.gson.fromJson(payClient.query(pay.getClass().getSimpleName(), writer.gson.toJson(pay)),
                new TypeToken<List<Pay>>(){}.getType());

        Collections.sort(pays, new Comparator<Pay>() {
            @Override
            public int compare(Pay o1, Pay o2) {
                if (o1.getId().compareTo(o2.getId()) > 0) {
                    return 1;
                } else if(o1.getId().compareTo(o2.getId()) < 0) {
                    return -1;
                }

                return 0;
            }
        });

        return pays.isEmpty() ? null : pays.get(0);
    }

    public String productStateModify(Audit audit, Integer state) {
        Product stateProduct = new Product();
        stateProduct.setId(audit.getEntityId());
        stateProduct.setState(state);

        return erpDao.updateById(stateProduct.getId(), stateProduct);
    }

    public String queryTargetEntity(String targetEntity, String entity, Object queryObject, int position, int rowNum) {
        String result = "";

        if (targetEntity.equalsIgnoreCase(Purchase.class.getSimpleName()) &&
                entity.equalsIgnoreCase(Purchase.class.getSimpleName())) {
            List<Purchase> purchases = (List<Purchase>)erpDao.complexQuery(Purchase.class,
                    writer.gson.fromJson(writer.gson.toJson(queryObject), new TypeToken<Map<String, String>>(){}.getType()), position, rowNum);

            Purchase tempPurchase = new Purchase();
            PurchaseDetail tempPurchaseDetail = new PurchaseDetail();

            for (int i = 0; i < purchases.size(); i++) {
                tempPurchase.setId(purchases.get(i).getId());
                tempPurchaseDetail.setPurchase(tempPurchase);

                List<PurchaseDetail> details = erpDao.query(tempPurchaseDetail);

                for (int j = 0; j < details.size(); j++) {
                    if (details.get(j).getPurchaseDetailProducts() != null && !details.get(j).getPurchaseDetailProducts().isEmpty()) {
                        Set<PurchaseDetailProduct> detailProducts = new HashSet<>();

                        for (PurchaseDetailProduct detailProduct : details.get(j).getPurchaseDetailProducts()) {
                            Product product = (Product) erpDao.queryById(detailProduct.getProduct().getId(), detailProduct.getProduct().getClass());
                            if (product != null && (product.getState().compareTo(ErpConstant.product_state_purchase_close) == 0 ||
                                    product.getState().compareTo(ErpConstant.product_state_stockOut) == 0)) {

                                    details.get(j).setProduct(product);
                                    detailProducts.add(detailProduct);
                            }
                        }

                        if (!detailProducts.isEmpty()) {
                            details.get(j).setPurchaseDetailProducts(detailProducts);
                        } else {
                            details.remove(details.get(j));
                            j--;
                        }

                    } else {
                        details.remove(details.get(j));
                        j--;
                    }
                }

                if (!details.isEmpty()) {
                    for (PurchaseDetail detail : details) {
                        if (!detail.getUnit().equals(ErpConstant.unit_g) && !detail.getUnit().equals(ErpConstant.unit_kg) &&
                                !detail.getUnit().equals(ErpConstant.unit_ct) && !detail.getUnit().equals(ErpConstant.unit_oz)) {
                            detail.setQuantity((float)detail.getPurchaseDetailProducts().size());
                        }
                    }

                    purchases.get(i).setDetails(new HashSet<>(details));
                } else {
                    purchases.remove(purchases.get(i));
                    i--;
                }
            }

            result = writer.gson.toJson(purchases);


        } else if (targetEntity.equalsIgnoreCase(PurchaseDetail.class.getSimpleName()) &&
                entity.equalsIgnoreCase(Product.class.getSimpleName())) {
            Map<String, String> queryParameters = writer.gson.fromJson(writer.gson.toJson(queryObject), new TypeToken<Map<String, String>>(){}.getType());
            StockInOut stockInOut = writer.gson.fromJson(queryParameters.get(StockInOut.class.getSimpleName().substring(0,1).toLowerCase()+StockInOut.class.getSimpleName().substring(1)), StockInOut.class);

            List products;
            if (stockInOut == null) {
                products = erpDao.complexQuery(Product.class, queryParameters, position, rowNum);

            } else {
                Class[] clazzs = {Product.class, ProductType.class, ProductDescribe.class};
                Map<String, List<Object>> results = erpDao.queryBySql(getStockInProductsByWarehouseComplexSql(queryParameters, stockInOut, position, rowNum), clazzs);
                products = results.get(Product.class.getName());
                List<Object> types = results.get(ProductType.class.getName());
                List<Object> describes = results.get(ProductDescribe.class.getName());

                int ii = 0;
                for (Object ele : products) {
                    ((Product)ele).setType((ProductType) types.get(ii));
                    ((Product)ele).setDescribe((ProductDescribe) describes.get(ii));
                    ii++;
                }
            }

            if (!products.isEmpty()) {
                List<PurchaseDetail> details = new ArrayList<>();

                PurchaseDetailProduct detailProduct = new PurchaseDetailProduct();
                for (int i = 0; i < products.size(); i++) {
                    detailProduct.setProduct((Product) products.get(i));

                    List<PurchaseDetailProduct> dbDetailProducts = erpDao.query(detailProduct);
                    if (dbDetailProducts != null && !dbDetailProducts.isEmpty()) {
                        PurchaseDetail detail = (PurchaseDetail) erpDao.queryById(dbDetailProducts.get(0).getPurchaseDetail().getId(), dbDetailProducts.get(0).getPurchaseDetail().getClass());

                        if (detail != null) {
                            detail.setProduct((Product) products.get(i));
                            detail.setPurchaseDetailProducts(new HashSet<>());
                            detail.getPurchaseDetailProducts().add(dbDetailProducts.get(0));
                            details.add(detail);
                        }
                    }

                }

                result = writer.gson.toJson(details);
            }
        }

        return result;
    }

    public List privateQuery(String entity, String json, int position, int rowNum) {
        if (entity.equalsIgnoreCase(Stock.class.getSimpleName())) {
            Class[] clazzs = {Stock.class, Product.class, Warehouse.class};
            Map<String, List<Object>> results = erpDao.queryBySql(getStockComplexSql(json, position, rowNum), clazzs);

            List<Object> stocks = results.get(Stock.class.getName());
            List<Object> products = results.get(Product.class.getName());
            List<Object> warehouses = results.get(Warehouse.class.getName());

            int i = 0;
            for (Object stock : stocks) {
                ((Stock)stock).setProduct((Product) products.get(i));
                ((Stock)stock).setWarehouse((Warehouse) warehouses.get(i));

                i++;
            }

            return stocks;

        } else if (entity.equalsIgnoreCase(Product.class.getSimpleName())) {
            Class[] clazzs = {Product.class, ProductType.class, Supplier.class, ProductDescribe.class, StockInOutDetailProduct.class, StockInOutDetail.class, StockInOut.class};
            Map<String, List<Object>> results = erpDao.queryBySql(getProductComplexSql(json, position, rowNum), clazzs);

            List<Object> products = results.get(Product.class.getName());
            List<Object> types = results.get(ProductType.class.getName());
            List<Object> suppliers = results.get(Supplier.class.getName());
            List<Object> describes = results.get(ProductDescribe.class.getName());

            List<Object> stockInOutDetails = results.get(StockInOutDetail.class.getName());
            List<Object> stockInOuts = results.get(StockInOut.class.getName());

            int i = 0;
            for (Object stockInOutDetail : stockInOutDetails) {
                Product product = (Product) products.get(i);
                product.setType((ProductType) types.get(i));
                product.setSupplier((Supplier) suppliers.get(i));
                product.setDescribe((ProductDescribe) describes.get(i));

                ((StockInOutDetail)stockInOutDetail).setProduct(product);
                ((StockInOutDetail)stockInOutDetail).setStockInOut((StockInOut) stockInOuts.get(i));

                i++;
            }

            return stockInOutDetails;

        } else if (entity.equalsIgnoreCase(ProductDescribe.class.getSimpleName())) {
            return erpDao.queryBySql(getProductDescribeSql(json, position, rowNum), ProductDescribe.class);
        }

        return new ArrayList();
    }

    public BigInteger privateRecordNum(String entity, String json){
        String sql = "";

        if (entity.equalsIgnoreCase(Stock.class.getSimpleName())) {
            sql = getStockComplexSql(json, 0, -1);

        } else if (entity.equalsIgnoreCase(Product.class.getSimpleName())) {
            sql = getProductComplexSql(json, 0, -1);

        } else if (entity.equalsIgnoreCase(ProductDescribe.class.getSimpleName())) {
            sql = getProductDescribeSql(json, 0, -1);
        }

        sql = "select count(t.id) from " + sql.split(" from ")[1];
        return (BigInteger)sessionFactory.getCurrentSession().createSQLQuery(sql).uniqueResult();
    }

    private String getStockComplexSql(String json, int position, int rowNum) {
        String sql = "";

        try {
            Map<String, Object> queryParameters = writer.gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());

            String stockSql = objectToSql.generateComplexSqlByAnnotation(Stock.class,
                    writer.gson.fromJson(writer.gson.toJson(queryParameters.get(Stock.class.getSimpleName().toLowerCase())),
                            new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType()), position, rowNum);

            String selectSql = "", fromSql = "", whereSql = "", sortNumSql = "";

            String[] sqlParts = erpDao.getSqlPart(stockSql, Stock.class);
            selectSql = sqlParts[0];
            fromSql = sqlParts[1];
            whereSql = sqlParts[2];
            sortNumSql = sqlParts[3];

            selectSql += ", " + erpDao.getSelectColumns("t11", Product.class);
            fromSql += ", " + objectToSql.getTableName(Product.class) + " t11 ";
            if (!whereSql.trim().equals("")) {
                whereSql += " and ";
            }
            whereSql += " t11." + objectToSql.getColumn(Product.class.getDeclaredField("no")) +
                    " = t." + objectToSql.getColumn(Stock.class.getDeclaredField("productNo"));

            if (queryParameters.get(Product.class.getSimpleName().toLowerCase()) != null) {
                String productSql = objectToSql.generateComplexSqlByAnnotation(Product.class,
                        writer.gson.fromJson(writer.gson.toJson(queryParameters.get(Product.class.getSimpleName().toLowerCase())),
                                new com.google.gson.reflect.TypeToken<Map<String, String>>() {
                                }.getType()), position, rowNum);

                productSql = productSql.substring(0, productSql.indexOf(" order by "));
                productSql = productSql.replace(" t ", " t11 ").replace(" t.", " t11.");

                if (productSql.contains(" where ")) {
                    String[] parts = productSql.split(" where ");
                    String[] tables = parts[0].split(" from ")[1].split(" t11 ");

                    if (tables.length > 1) {
                        fromSql += tables[1];
                    }
                    whereSql += " and " + parts[1];
                }

            }

            selectSql += ", " + erpDao.getSelectColumns("t12", Warehouse.class);
            fromSql += ", " + objectToSql.getTableName(Warehouse.class) + " t12 ";
            whereSql += " and t12." + objectToSql.getColumn(Warehouse.class.getDeclaredField("id")) +
                    " = t." + objectToSql.getColumn(Stock.class.getDeclaredField("warehouse"));


            if (whereSql.indexOf(" and") == 0) {
                whereSql = whereSql.substring(" and".length());
            }

            sql = "select " + selectSql + " from " + fromSql + " where " + whereSql + " order by " + sortNumSql;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return sql;
    }

    private String getProductComplexSql(String json, int position, int rowNum) {
        String sql = "";

        try {
            Map<String, Object> queryParameters = writer.gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());

            String productSql = objectToSql.generateComplexSqlByAnnotation(Product.class,
                    writer.gson.fromJson(writer.gson.toJson(queryParameters.get(Product.class.getSimpleName().toLowerCase())),
                            new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType()), position, rowNum);

            String selectSql = "", fromSql = "", whereSql = "", sortNumSql = "";

            String[] sqlParts = erpDao.getSqlPart(productSql, Product.class);
            selectSql = sqlParts[0];
            fromSql = sqlParts[1];
            whereSql = sqlParts[2];
            sortNumSql = sqlParts[3];


            selectSql += ", " + erpDao.getSelectColumns("t13", ProductType.class);
            fromSql += ", " + objectToSql.getTableName(ProductType.class) + " t13 ";
            if (!whereSql.trim().equals("")) {
                whereSql += " and ";
            }
            whereSql += " t13." + objectToSql.getColumn(ProductType.class.getDeclaredField("id")) +
                    " = t." + objectToSql.getColumn(Product.class.getDeclaredField("type"));

            selectSql += ", " + erpDao.getSelectColumns("t12", Supplier.class);
            fromSql += ", " + objectToSql.getTableName(Supplier.class) + " t12 ";
            whereSql += " and t12." + objectToSql.getColumn(Supplier.class.getDeclaredField("id")) +
                    " = t." + objectToSql.getColumn(Product.class.getDeclaredField("supplier"));

            selectSql += ", " + erpDao.getSelectColumns("t14", ProductDescribe.class);
            fromSql += ", " + objectToSql.getTableName(ProductDescribe.class) + " t14 ";
            whereSql += " and t14." + objectToSql.getColumn(ProductDescribe.class.getDeclaredField("id")) +
                    " = t." + objectToSql.getColumn(Product.class.getDeclaredField("describe"));


            selectSql += ", " + erpDao.getSelectColumns("t11", StockInOutDetailProduct.class);
            fromSql += ", " + objectToSql.getTableName(StockInOutDetailProduct.class) + " t11 ";
            whereSql += " and t11." + objectToSql.getColumn(StockInOutDetailProduct.class.getDeclaredField("product")) +
                    " = t." + objectToSql.getColumn(Product.class.getDeclaredField("id"));

            selectSql += ", " + erpDao.getSelectColumns("t111", StockInOutDetail.class);
            fromSql += ", " + objectToSql.getTableName(StockInOutDetail.class) + " t111 ";
            whereSql += " and t111." + objectToSql.getColumn(StockInOutDetail.class.getDeclaredField("id")) +
                    " = t11." + objectToSql.getColumn(StockInOutDetailProduct.class.getDeclaredField("stockInOutDetail"));

            selectSql += ", " + erpDao.getSelectColumns("t22", StockInOut.class);
            fromSql += ", " + objectToSql.getTableName(StockInOut.class) + " t22 ";
            whereSql += " and t22." + objectToSql.getColumn(StockInOut.class.getDeclaredField("id")) +
                    " = t111." + objectToSql.getColumn(StockInOutDetail.class.getDeclaredField("stockInOut")) +
                    " and t22.state = " + ErpConstant.stockInOut_state_backup;

            String stockInOutEntity = StockInOut.class.getSimpleName().substring(0,1).toLowerCase()+StockInOut.class.getSimpleName().substring(1);
            if (queryParameters.get(stockInOutEntity) != null) {
                String stockInOutSql = objectToSql.generateComplexSqlByAnnotation(StockInOut.class,
                        writer.gson.fromJson(writer.gson.toJson(queryParameters.get(stockInOutEntity)),
                                new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType()), position, rowNum);

                stockInOutSql = stockInOutSql.substring(0, stockInOutSql.indexOf(" order by "));
                stockInOutSql = stockInOutSql.replace(" t ", " t22 ").replace(" t.", " t22.");

                if (stockInOutSql.contains(" where ")) {
                    String[] parts = stockInOutSql.split(" where ");
                    String[] tables = parts[0].split(" from ")[1].split(" t22 ");

                    if (tables.length > 1) {
                        fromSql += tables[1];
                    }
                    whereSql += " and " + parts[1];
                }
            }


            if (whereSql.indexOf(" and") == 0) {
                whereSql = whereSql.substring(" and".length());
            }

            sql = "select " + selectSql + " from " + fromSql + " where " + whereSql + " order by " + sortNumSql;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return sql;
    }

    private String getStockInProductsByWarehouseComplexSql(Map<String, String> queryParameters, StockInOut stockInOut, int position, int rowNum) {
        String sql = "";

        try {
            String productSql = objectToSql.generateComplexSqlByAnnotation(Product.class, queryParameters, position, rowNum);

            String selectSql = "", fromSql = "", whereSql = "", sortNumSql = "";

            String[] sqlParts = erpDao.getSqlPart(productSql, Product.class);
            selectSql = sqlParts[0];
            fromSql = sqlParts[1];
            whereSql = sqlParts[2];
            sortNumSql = sqlParts[3];


            selectSql += ", " + erpDao.getSelectColumns("t13", ProductType.class);
            fromSql += ", " + objectToSql.getTableName(ProductType.class) + " t13 ";
            if (!whereSql.trim().equals("")) {
                whereSql += " and ";
            }
            whereSql += " t13." + objectToSql.getColumn(ProductType.class.getDeclaredField("id")) +
                    " = t." + objectToSql.getColumn(Product.class.getDeclaredField("type"));

            selectSql += ", " + erpDao.getSelectColumns("t14", ProductDescribe.class);
            fromSql += ", " + objectToSql.getTableName(ProductDescribe.class) + " t14 ";
            whereSql += " and t14." + objectToSql.getColumn(ProductDescribe.class.getDeclaredField("id")) +
                    " = t." + objectToSql.getColumn(Product.class.getDeclaredField("describe"));


            selectSql += ", " + erpDao.getSelectColumns("t11", StockInOutDetailProduct.class);
            fromSql += ", " + objectToSql.getTableName(StockInOutDetailProduct.class) + " t11 ";
            whereSql += " and t11." + objectToSql.getColumn(StockInOutDetailProduct.class.getDeclaredField("product")) +
                    " = t." + objectToSql.getColumn(Product.class.getDeclaredField("id"));

            selectSql += ", " + erpDao.getSelectColumns("t111", StockInOutDetail.class);
            fromSql += ", " + objectToSql.getTableName(StockInOutDetail.class) + " t111 ";
            whereSql += " and t111." + objectToSql.getColumn(StockInOutDetail.class.getDeclaredField("id")) +
                    " = t11." + objectToSql.getColumn(StockInOutDetailProduct.class.getDeclaredField("stockInOutDetail"));

            selectSql += ", " + erpDao.getSelectColumns("t22", StockInOut.class);
            fromSql += ", " + objectToSql.getTableName(StockInOut.class) + " t22 ";
            whereSql += " and t22." + objectToSql.getColumn(StockInOut.class.getDeclaredField("id")) +
                    " = t111." + objectToSql.getColumn(StockInOutDetail.class.getDeclaredField("stockInOut"));

            String stockInOutSql = objectToSql.generateSelectSqlByAnnotation(stockInOut);

            stockInOutSql = stockInOutSql.substring(0, stockInOutSql.indexOf(" order by "));
            stockInOutSql = stockInOutSql.replace(" t ", " t22 ").replace(" t.", " t22.");

            if (stockInOutSql.contains(" where ")) {
                String[] parts = stockInOutSql.split(" where ");
                String[] tables = parts[0].split(" from ")[1].split(" t22 ");

                if (tables.length > 1) {
                    fromSql += tables[1];
                }
                whereSql += " and " + parts[1];
            }


            if (whereSql.indexOf(" and") == 0) {
                whereSql = whereSql.substring(" and".length());
            }

            sql = "select " + selectSql + " from " + fromSql + " where " + whereSql + " order by " + sortNumSql;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return sql;
    }

    private String getProductDescribeSql(String json, int position, int rowNum) {
        Map<String, String> queryParameters = writer.gson.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
        String sql = objectToSql.generateComplexSqlByAnnotation(ProductDescribe.class, queryParameters, position, rowNum);

        String[] sqlParts = null;

        try {
            if (sql.contains(" where ")) {
                sqlParts = objectToSql.generateComplexSqlByAnnotation(ProductDescribe.class, queryParameters, position, rowNum).split(" where ");
                sql = sqlParts[0] + " where " + objectToSql.getColumn(ProductDescribe.class.getDeclaredField("editor")) + " is not null and " + sqlParts[1];

            } else {
                sqlParts = objectToSql.generateComplexSqlByAnnotation(ProductDescribe.class, queryParameters, position, rowNum).split(" order by ");
                sql = sqlParts[0] + " where " + objectToSql.getColumn(ProductDescribe.class.getDeclaredField("editor")) + " is not null order by " + sqlParts[1];
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return sql;
    }

    /**
     *
     售价浮动权限
     1.参照价格：销售人员唯一参照售价以官网标注价格为准。
     2.客户报价规定：销售人员给客户的第一报价必须和官网标注价格一致。
     3.售价浮动标准：普通销售人员可在自主售价浮动权限内决定成交售价，但需尽力维持官网标注价格。
     4.普通销售人员售价浮动权限：
     （1）售价1千以内（含）的商品，无浮动权限，维持原价出售，且商品无证书，如客户需要则另加费用50元。
     （2）售价1~3千内（含）的商品，浮动100元以内，可自主决定。
     （3）售价3~1万（含）的商品，浮动300元以内，可自主决定。
     （4）售价1万~3万（含）的商品，浮动500元以内，可自主决定。
     （5）售价3万~10万（含）的商品，浮动1000元以内，可自主决定。
     （6）售价10万以上的商品，浮动需申请，不可自主决定。
     （7）浮动金额可以直接优惠给客户在销售成交价上体现，也可以折算同等浮动金额销售价的赠品赠予。
     （8）浮动金额超出权限，需向主管以上申请批准。
     5.销售主管售价浮动权限
     （1）拥有普通销售人员售价浮动权限。
     （2）所有商品都可在标价的5%范围内自主决定是否浮动。
     （3）浮动金额超出权限，需向经理以上申请批准。
     6.销售经理售价浮动权限
     （1）所有商品都可在标价的10%范围内自主决定是否浮动。但工作职责包括如何提高商品售价的利润最大化。
     （2）浮动金额超出权限，需向总监以上申请批准。
     7.销售总监售价浮动权限
     （1）所有商品都可在标价的15%范围内自主决定是否浮动。但工作职责主要是商品售价的利润最大化。
     （2）浮动金额超出权限，需向副总经理以上申请批准。
     * @param json
     * @param operator
     * @return
     */
    public String saveOrUpdatePriceChange(String json, String operator) {
        String result = CommonConstant.fail;

        ProductPriceChange priceChange = writer.gson.fromJson(json, ProductPriceChange.class);
        Product queryProduct = new Product();
        queryProduct.setNo(priceChange.getProductNo());
        queryProduct.setFatePrice(priceChange.getPrePrice());
        queryProduct.setState(ErpConstant.product_state_onSale);
        Product product = (Product) erpDao.query(queryProduct).get(0);

        if (product.getFatePrice() > ErpConstant.price_1000) {
            User user = (User)erpDao.getFromRedis(
                    (String) erpDao.getFromRedis(CommonConstant.sessionId + CommonConstant.underline + priceChange.getSessionId()));

            if (user != null) {
                String auditEntity = null;

                if (priceChange.getState().compareTo(ErpConstant.product_price_change_state_save) != 0) {
                    String resources = (String)erpDao.getFromRedis(user.getUsername() + CommonConstant.underline + CommonConstant.resources);

                    auditEntity = getPriceChangeAuditEntity(priceChange, product, resources);

                    if (auditEntity == null) {
                        priceChange.setState(ErpConstant.product_price_change_state_use);
                    } else {
                        priceChange.setState(ErpConstant.product_price_change_state_apply);
                        priceChange.setIsAudit(CommonConstant.Y);
                    }
                }

                priceChange.setInputDate(dateUtil.getSecondCurrentTimestamp());
                priceChange.setUser(user);
                if (operator.equals(CommonConstant.save)) {
                    result += erpDao.save(priceChange);
                } else {
                    result += erpDao.updateById(priceChange.getId(), priceChange);
                }

                if (auditEntity != null) {
                    result += launchAuditFlow(auditEntity, priceChange.getId(), priceChange.getNo(),
                            "申请商品：" + product.getNo() + "的销售价格浮动为：" + priceChange.getPrice(),
                            "请审核商品：" + product.getNo() + "的销售价格：" + priceChange.getPrice(),
                            user);
                }

            } else {
                result += CommonConstant.fail + ",查询不到设置申请调价的用户，调价失败";
            }


        } else {
            result += CommonConstant.fail + "," + ErpConstant.price_1000 + "元以下的商品不能调价";
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public String getPriceChangeAuditEntity(ProductPriceChange priceChange, Product product, String resources) {
        String entity = null;

        if (resources.contains(ErpConstant.privilege_resource_uri_price_change_saler)){
            if(isSalePriceNeedAudit(priceChange.getPrice(), product.getFatePrice())) {
                entity = AuditFlowConstant.business_price_change_saler;
            }
        }

        if (resources.contains(ErpConstant.privilege_resource_uri_price_change_charger)){
            if(priceChange.getPrice()/product.getFatePrice() < ErpConstant.price_percent_95) {
                entity = AuditFlowConstant.business_price_change_charger;
            }
        }

        if (resources.contains(ErpConstant.privilege_resource_uri_price_change_manager)){
            if(priceChange.getPrice()/product.getFatePrice() < ErpConstant.price_percent_90) {
                entity = AuditFlowConstant.business_price_change_manager;
            }
        }

        if (resources.contains(ErpConstant.privilege_resource_uri_price_change_director)){
            if(priceChange.getPrice()/product.getFatePrice() < ErpConstant.price_percent_85) {
                entity = AuditFlowConstant.business_price_change_director;
            }
        }

        return entity;
    }

    public boolean isSalePriceNeedAudit(Float salePrice, Float price) {
        Float subtractPrice =  new BigDecimal(Float.toString(price)).subtract(new BigDecimal(Float.toString(salePrice))).floatValue();

        if (price > ErpConstant.price_1000 && price <= ErpConstant.price_3000 &&
                subtractPrice <= ErpConstant.subtract_price_100) {
            return false;
        }

        if (price > ErpConstant.price_3000 && price <= ErpConstant.price_10000 &&
                subtractPrice <= ErpConstant.subtract_price_300) {
            return false;
        }

        if (price > ErpConstant.price_10000 && price <= ErpConstant.price_30000 &&
                subtractPrice <= ErpConstant.subtract_price_500) {
            return false;
        }

        if (price > ErpConstant.price_30000 && price <= ErpConstant.price_100000 &&
                subtractPrice <= ErpConstant.subtract_price_1000) {
            return false;
        }

        return true;
    }

    public com.hzg.sys.User getRandomStockOutUser() {
        PrivilegeResource privilegeResource = new PrivilegeResource();
        privilegeResource.setUri(ErpConstant.privilege_resource_uri_print_expressWaybill);
        List<com.hzg.sys.User> users = writer.gson.fromJson(sysClient.getUsersByUri(writer.gson.toJson(privilegeResource)),
                new com.google.gson.reflect.TypeToken<List<User>>(){}.getType());
        return users.get((int)System.currentTimeMillis()%users.size());
    }

    public Float getCanSellProductQuantity(String productNo) {
        Stock stock = new Stock();
        stock.setProductNo(productNo);
        stock.setState(ErpConstant.stock_state_valid);
        List<Stock> stocks = erpDao.query(stock);
        BigDecimal canSellQuantity = new BigDecimal(0);

        for (Stock ele : stocks) {
            canSellQuantity.add(new BigDecimal(Float.toString(ele.getQuantity())));
        }

        return canSellQuantity.floatValue();
    }

    public String changeProductState(List<Integer> productIds, Integer allowState, Integer toState) {
        String result = CommonConstant.fail;

        for (Integer id : productIds) {
            Product stateProduct = (Product) erpDao.queryById(id, Product.class);
            if (stateProduct.getState().compareTo(allowState) != 0) {
                result += CommonConstant.fail;

                if (allowState.compareTo(ErpConstant.product_state_edit) == 0) {
                    result += ", 商品 " + stateProduct.getNo() + "不是编辑状态，不能上架商品";

                } else if (allowState.compareTo(ErpConstant.product_state_onSale) == 0) {
                    result += ", 商品 " + stateProduct.getNo() + "不是在售状态，不能下架商品";
                }

                break;
            }
        }

        if (!result.contains(CommonConstant.fail+CommonConstant.fail)) {
            Product product = new Product();
            for (Integer id : productIds) {
                product.setState(toState);
                result += erpDao.updateById(id, product);
            }
        }

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public StockInOut queryStockInOut(Integer id) {
        StockInOut stockInOut = (StockInOut) erpDao.queryById(id, StockInOut.class);

        for (StockInOutDetail detail : stockInOut.getDetails()) {
            StockInOutDetailProduct detailProduct = new StockInOutDetailProduct();
            detailProduct.setStockInOutDetail(detail);
            detail.setStockInOutDetailProducts(new HashSet<>(erpDao.query(detailProduct)));
        }

        return stockInOut;
    }

    public String isCanStockIn(StockInOut stockIn) {
        String result = null;

        for (StockInOutDetail detail : stockIn.getDetails()) {
            for (StockInOutDetailProduct detailProduct : detail.getStockInOutDetailProducts()) {
                Product dbProduct = (Product) erpDao.queryById(detailProduct.getProduct().getId(), detailProduct.getProduct().getClass());

                if (dbProduct.getState().compareTo(ErpConstant.product_state_purchase_close) != 0 &&
                        dbProduct.getState().compareTo(ErpConstant.product_state_stockOut) != 0) {
                    if (detail.getStockInOutDetailProducts().size() > 1) {
                        result = CommonConstant.fail + ",编号：" + dbProduct.getNo() + " 的" + detail.getStockInOutDetailProducts().size() + "件商品中，" +
                                "有不是采购完成或出库状态的商品，不能入库";
                    } else {
                        result = CommonConstant.fail + ",编号：" + dbProduct.getNo() + " 的商品不是采购完成或出库状态，不能入库";
                    }

                    break;
                }
            }
        }

        return result;
    }

    public String isCanStockOut(StockInOut stockOut) {
        String result = null;

        for (StockInOutDetail detail : stockOut.getDetails()) {
            for (StockInOutDetailProduct detailProduct : detail.getStockInOutDetailProducts()) {
                Product dbProduct = (Product) erpDao.queryById(detailProduct.getProduct().getId(), detailProduct.getProduct().getClass());

                if (dbProduct.getState().compareTo(ErpConstant.product_state_stockIn) != 0 &&
                        dbProduct.getState().compareTo(ErpConstant.product_state_onSale) != 0) {
                    result = CommonConstant.fail + ",编号：" + dbProduct.getNo() + " 的商品不是入库或在售状态，不能出库";
                    break;
                }
            }
        }

        return result;
    }

    public String isCanSaveStockInOut(StockInOut stockInOut) {
        String result = null;

        for (StockInOutDetail detail : stockInOut.getDetails()) {
            for (StockInOutDetailProduct detailProduct : detail.getStockInOutDetailProducts()) {
                List<StockInOutDetailProduct> dbDetailProducts = erpDao.query(detailProduct);

                for (StockInOutDetailProduct dbDetailProduct : dbDetailProducts) {
                    StockInOutDetail dbDetail = (StockInOutDetail) erpDao.queryById(dbDetailProduct.getStockInOutDetail().getId(), dbDetailProduct.getStockInOutDetail().getClass());
                    if (dbDetail.getStockInOut().getState().compareTo(ErpConstant.stockInOut_state_apply) == 0) {
                        result = CommonConstant.fail + ",入库申请单：" + dbDetail.getStockInOut().getNo() +"已经含有编号：" + dbDetail.getProductNo() +
                                " 的商品，不能重复申请入库";
                        break;
                    }
                }
            }
        }

        if (result == null) {
            if (stockInOut.getType().compareTo(ErpConstant.stockInOut_type_virtual_outWarehouse) < 0) {
                String msg = isCanStockIn(stockInOut);
                if (msg != null) {
                    result += msg;
                }
            } else {
                String msg = isCanStockOut(stockInOut);
                if (msg != null) {
                    result += msg;
                }
            }
        }

        return result;
    }

    public User getUserBySessionId(String sessionId){
        return (User)erpDao.getFromRedis((String)erpDao.getFromRedis(CommonConstant.sessionId + CommonConstant.underline + sessionId));
    }

    public String generateBarcodes(StockInOut stockInOut) {
        String barcodesImage = "<table border='1' cellpadding='0' cellspacing='0'><tr>";

        StockInOutDetail[] details = new StockInOutDetail[stockInOut.getDetails().size()];
        stockInOut.getDetails().toArray(details);
        int k = 0;

        for (int i = 0; i < details.length; i++) {
            StockInOutDetailProduct detailProduct = new StockInOutDetailProduct();
            detailProduct.setStockInOutDetail(details[i]);

            List<StockInOutDetailProduct> detailProducts = erpDao.query(detailProduct);
            for (int j = 0; j < detailProducts.size(); j++) {

                Product product = (Product) erpDao.queryById(detailProducts.get(j).getProduct().getId(), detailProducts.get(j).getProduct().getClass());
                barcodesImage += "<td  align='center' style='padding:10px'><img src='data:image/png;base64," + imageBase64.imageToBase64(barcodeUtil.generate(String.valueOf(product.getId()))) + "'/><br/>" +
                        product.getNo() + "</td>";

                if (k % 4 == 0 && k != 0) {
                    barcodesImage += "</tr>";

                    if (j < detailProducts.size()-1 || i < details.length-1) {
                        barcodesImage += "<tr>";
                    }
                }

                k++;
            }
        }

        barcodesImage += "</tr></table>";
        return barcodesImage;
    }

    public String downloadSfExpressWayBillByStockInOut(StockInOut stockInOut) {
        String printContent = "";

        printContent = downloadSfWaybill(getSfExpressOrderByStockInOut(stockInOut));
        if (printContent == "") {
            printContent += "暂时查询不到快递单，无法打印，请30秒后重试<br/><br/>";
        }

        return printContent;
    }

    /**
     * 根据收件人及出库单产生顺丰快递单
     * @param receiverInfo
     * @param stockOut
     * @return
     */
    public String generateSfExpressOrderByReceiverAndStockOut(ExpressDeliver receiverInfo, StockInOut stockOut){
        logger.info("generateSfExpressOrderByReceiverAndStockOut start: receiverInfo:" + receiverInfo.toString() + ", stockOut:" + stockOut.getId());
        String result;

        /**
         * 重复打印时，由于第一次已经生成快递单，不再需要生成顺丰快递单
         */
        ExpressDeliver expressDeliver = getSfExpressOrderByStockInOut(stockOut);
        if (!isReceiverInfoSame(receiverInfo, expressDeliver)) {
            expressDeliver = generateExpressDeliver(receiverInfo, stockOut);
            result = expressDeliverOrder(expressDeliver);

        } else {
            result = CommonConstant.success;
        }

        logger.info("generateSfExpressOrderByReceiverAndStockOut end");
        return result;
    }

    public ExpressDeliver generateExpressDeliver(ExpressDeliver receiverInfo, StockInOut stockOut) {
        logger.info("generateExpressDeliver start, receiverInfo:" + receiverInfo.toString() + ",  stockInOut:" + stockOut.toString());

        User sender = (User) erpDao.queryById(stockOut.getInputer().getId(), stockOut.getInputer().getClass());
        Warehouse warehouse = (Warehouse) erpDao.queryById(stockOut.getWarehouse().getId(), stockOut.getWarehouse().getClass());

        ExpressDeliver expressDeliver = new ExpressDeliver();
        expressDeliver.setDeliver(ErpConstant.deliver_sfExpress);
        expressDeliver.setType(ErpConstant.deliver_sfExpress_type);
        expressDeliver.setDate(receiverInfo.getDate());
        expressDeliver.setState(ErpConstant.express_state_sending);

        expressDeliver.setReceiver(receiverInfo.getReceiver());
        expressDeliver.setReceiverAddress(receiverInfo.getReceiverAddress());
        expressDeliver.setReceiverCity(receiverInfo.getReceiverCity());
        expressDeliver.setReceiverProvince(receiverInfo.getReceiverProvince());
        expressDeliver.setReceiverCountry(receiverInfo.getReceiverCountry());
        expressDeliver.setReceiverCompany(receiverInfo.getReceiverCompany());
        expressDeliver.setReceiverMobile(receiverInfo.getReceiverMobile());
        expressDeliver.setReceiverTel(receiverInfo.getReceiverTel());
        expressDeliver.setReceiverPostCode(receiverInfo.getReceiverPostCode());

        expressDeliver.setSender(sender.getName());
        expressDeliver.setSenderAddress(warehouse.getCompany().getAddress());
        expressDeliver.setSenderCity(warehouse.getCompany().getCity());
        expressDeliver.setSenderProvince(warehouse.getCompany().getProvince());
        expressDeliver.setSenderCountry(warehouse.getCompany().getCountry());
        expressDeliver.setSenderCompany(warehouse.getCompany().getName());
        expressDeliver.setSenderMobile(sender.getMobile());
        expressDeliver.setSenderTel(warehouse.getCompany().getPhone());
        expressDeliver.setSenderPostCode(warehouse.getCompany().getPostCode());

        Set<ExpressDeliverDetail> deliverDetails = new HashSet<>();

        for (StockInOutDetail detail : stockOut.getDetails()) {
            StockInOutDetailProduct detailProduct = new StockInOutDetailProduct();
            detailProduct.setStockInOutDetail(detail);

            List<StockInOutDetailProduct> detailProducts = erpDao.query(detailProduct);
            Product product = (Product) erpDao.queryById(detailProducts.get(0).getProduct().getId(), detailProducts.get(0).getProduct().getClass());

            ExpressDeliverDetail expressDeliverDetail = new ExpressDeliverDetail();
            expressDeliverDetail.setExpressNo(ErpConstant.no_expressDelivery_perfix + erpDao.getSfTransMessageId());
            expressDeliverDetail.setProductNo(product.getNo());
            expressDeliverDetail.setQuantity(detail.getQuantity());
            expressDeliverDetail.setUnit(detail.getUnit());
            expressDeliverDetail.setPrice(product.getFatePrice());
            expressDeliverDetail.setState(ErpConstant.express_detail_state_unSend);

            Set<ExpressDeliverDetailProduct> deliverDetailProducts = new HashSet<>();
            for (StockInOutDetailProduct ele : detailProducts) {
                ExpressDeliverDetailProduct deliverDetailProduct = new ExpressDeliverDetailProduct();
                deliverDetailProduct.setProduct(ele.getProduct());
                deliverDetailProducts.add(deliverDetailProduct);
            }

            expressDeliverDetail.setExpressDeliverDetailProducts(deliverDetailProducts);
            deliverDetails.add(expressDeliverDetail);
            expressDeliver.setDetails(deliverDetails);
        }

        logger.info("generateExpressDeliver end");
        return expressDeliver;
    }

    boolean isReceiverInfoSame (ExpressDeliver receiverInfo, ExpressDeliver expressDeliver) {
        try {
            if (receiverInfo.getReceiver().equals(expressDeliver.getReceiver()) &&
                    receiverInfo.getReceiverAddress().equals(expressDeliver.getReceiverAddress()) &&
                    receiverInfo.getReceiverCity().equals(expressDeliver.getReceiverCity()) &&
                    receiverInfo.getReceiverCountry().equals(expressDeliver.getReceiverCountry()) &&
                    receiverInfo.getReceiverProvince().equals(expressDeliver.getReceiverProvince()) &&
                    dateUtil.getSimpleDateFormat().format(receiverInfo.getDate()).equals(dateUtil.getSimpleDateFormat().format(expressDeliver.getDate()))) {

                if (receiverInfo.getReceiverMobile() != null && expressDeliver.getReceiverMobile() != null) {
                    if (receiverInfo.getReceiverMobile().equals(expressDeliver.getReceiverMobile())) {
                        return true;
                    }
                }

                if (receiverInfo.getReceiverTel() != null && expressDeliver.getReceiverTel() != null) {
                    if (receiverInfo.getReceiverTel().equals(expressDeliver.getReceiverTel())) {
                        return true;
                    }
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    public String expressDeliverOrder(ExpressDeliver expressDeliver) {
        String result = CommonConstant.fail;

        result += erpDao.save(expressDeliver);

        for (ExpressDeliverDetail expressDeliverDetail : expressDeliver.getDetails()) {
            expressDeliverDetail.setExpressDeliver(expressDeliver);
            result += erpDao.save(expressDeliverDetail);

            for (ExpressDeliverDetailProduct expressDeliverDetailProduct : expressDeliverDetail.getExpressDeliverDetailProducts()) {
                expressDeliverDetailProduct.setExpressDeliverDetail(expressDeliverDetail);
                result += erpDao.save(expressDeliverDetailProduct);
            }
        }

        result += sfExpressOrder(expressDeliver);

        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    /**
     * 调用顺丰快递单接口，在顺丰系统生成快递单
     * @param expressDeliver
     *
     *  快递单内容
     *
     *  请求报文内容
    字段名称 类型 是否
    必须
    描述
    orderId  String(56)  是  客户订单号，最大长度限于 56 位，该字段客户
    可自行定义，请尽量命名的规范有意义，如
    SFAPI20160830001，订单号作为客户下单的凭
    证， 不允许重复提交 订单号。
    expressType  String(5)  是  常用快件产品类别：
    类别 描述
    1  顺丰标快
    2  顺丰特惠
    3  电商特惠
    5  顺丰次晨
    6  顺丰即日
    7  电商速配
    15  生鲜速配
    payMethod  Number(1)  是  付款方式：
    类别 描述
    1  寄付现结（可不传 custId）
    /寄付月结 【默认值】 (必传
    custId)
    2  收方付
    3  第三方月结卡号支付
    isDoCall  Number(1)  否  是否下 call（通知收派员上门取件）
    类别 描述
    1  下 call
    0  不下 call【默认值】
    isGenBillno  Number(1)  否  是否申请运单号
    类别 描述
    1  申请【默认值】
    0  不申请
    isGenEletricPic  Number(1)  否  是否生成电子运单图片
    类别 描述
    1  生成【默认值】
    0  不生成
    custId  String(20)  是  顺丰月结卡号
    顺丰开放平台接口接入规范 V1.0
    12  顺丰科技
    2016 年 08 月 30 日
    payArea  String(20)  否  月结卡号对应的网点，如果付款方式为第三方月
    结卡号支付，则必填
    sendStartTime  String(18)
    否  要求上门取件开始时间，格式：YYYY-MM-DD
    HH24:MM:SS，示例：2016-8-30 09:30:00，
    默认值为系统收到订单的系统时间
    needReturnTrackingNo String(2)  否  是否需要签回单号
    类别 描述
    1  需要
    0  不需要【默认值】
    remark String(100) 否 备注，最大长度 30 个汉字
    deliverInfo  否  寄件方信息
    company  String(100)  否  寄件方公司名称
    如果不提供，将从系统默认配置获取
    contact  String(100)  否  寄件方联系人
    如果不提供，将从系统默认配置获取
    tel  String(20)  否  寄件方联系电话
    如果不提供，将从系统默认配置获取
    province  String(30)  否  寄件方所在省份，必须是标准的省名称称谓
    如： 广东省（省字不要省略）
    如果是直辖市，请直接传北京市、上海市等
    如果不提供，将从系统默认配置获取
    city  String(100)  否  寄件方所属城市名称，必须是标准的城市称谓
    如： 深圳市（市字不要省略）
    如果是直辖市，请直接传北京市、上海市等
    如果不提供，将从系统默认配置获取
    county  String(30)  否
    寄件人所在县/区，必须是标准的县/区称谓
    示例： 福田区（区字不要省略）
    如果不提供，将从系统默认配置获取
    address  String(200)  否  寄件方详细地址
    如：“福田区新洲十一街万基商务大厦 10 楼”
    如果不提供，将从系统默认配置获取
    shipperCode  String(30)  否  寄件方邮编代码
    mobile String(20) 否 寄件方手机
    consignee Info  收件方信息
    company  String(100)  是  到件方公司名称
    contact  String(100)  是  到件方联系人
    tel  String(20)  是  到件方联系电话
    province  String(30)  是  到件方所在省份，必须是标准的省名称称谓
    如：广东省（省字不要省略）
    如果是直辖市，请直接传北京市、上海市等
    city  String(100)  是  到件方所属城市名称，必须是标准的城市称谓
    如：深圳市（市字不要省略）
    如果是直辖市，请直接传北京市、上海市等
    county  String(30)  是
    到件人所在县/区，必须是标准的县/区称谓
    如：福田区（区字不要省略）
    address  String(200)  是  到件方详细地址
    如：“新洲十一街万基商务大厦 10 楼”
    shipperCode  String(30)  否  到件方邮编代码
    mobile String(20) 否 到件方手机
    顺丰开放平台接口接入规范 V1.0
    13  顺丰科技
    2016 年 08 月 30 日
    cargoInfo  货物信息
    parcelQuantity  Number(5)  否  包裹数，一个包裹对应一个运单号，如果是大于
    1 个包裹，则返回按照子母件的方式返回母运单
    号和子运单号。默认为 1
    cargo  String(4000)  是  货物名称，如果有多个货物，以英文逗号分隔，
    如：“手机,IPAD,充电器”
    cargoCount  String(4000)  否  货物数量，多个货物时以英文逗号分隔，且与货
    物名称一一对应
    如：2,1,3
    cargoUnit  String(4000)  否  货物单位，多个货物时以英文逗号分隔，且与货
    物名称一一对应
    如：个,台,本
    cargoWeight  String(4000)  否  货物重量，多个货物时以英文逗号分隔，且与货
    物名称一一对应
    如：1.0035,1.0,3.0
    cargoAmount  String(4000)  否  货物单价，多个货物时以英文逗号分隔，且与货
    物名称一一对应
    如：1000,2000,1500
    cargoTotalWeight Number(10,2) 否 订单货物总重量， 单位 KG， 如果提供此值， 必须>0
    addedServices  增值服务 （注意字段名称必须为英文字母大写 ）
    CUSTID  String(30)  否  代收货款月结卡号，如果选择 COD 增值服务-代
    收货款， 必填， 该项为代送货款使用的月结卡号，
    该项值必须在，COD 前设置（即先传 CUSTID 值再
    传 COD 值） 否则无效
    COD  String(20)  否  代收货款，value代收货款值，上限为20000，以
    原寄地所在区域币种为准，如中国大陆为人民
    币，香港为港币，保留1位小数，如 99.9 。
    value1为代收货款协议卡号（可能与月结卡号相
    同），
    如选择此服务，须增加CUSTID字段
    INSURE  String(30)  否  保价，value为声明价值(即该包裹的价值)
    MSG  String(30)  否  签收短信通知，value 为手机号码
    PKFREE  String(30)  否  包装费，value 为包装费费用.
    SINSURE String(30)  否  特殊保价，value 为服务费.
    SDELIVERY  String(30)  否  特殊配送，value为服务特殊配送服务费.
    SADDSERVICE  String(30)  否  特殊增值服务，value 特殊增值服务费
    5.1.1.2.1.2  响应 报文内容
    字段名称  类型  是否
    必须
    描述
    orderId  String  是  客户订单号
    filterLevel  String  否  筛单级别 0：不筛单 4：四级筛单
    orderTriggerCondi
    tion
    String  否  订单触发条件 1：上门收件 2 电子称 3：
    收件入仓 4：大客户装包 5：大客户装车
    remarkCode  String  否  01 ：下单操作成功 02：下单操作失败 03：
    订单号重复
     *
     */
    public String sfExpressOrder(ExpressDeliver expressDeliver) {
        logger.info("sfExpressOrder start, details:" + expressDeliver.toString());

        String result = CommonConstant.fail;

        //设置 uri
        String url = httpProxyDiscovery.getHttpProxyAddress() + sfExpress.getOrderUri();
        AppInfo appInfo = new AppInfo();
        appInfo.setAppId(sfExpress.getAppId());
        appInfo.setAppKey(sfExpress.getAppKey());
        appInfo.setAccessToken(getSfToken(sfExpress.getAppId(), sfExpress.getAppKey()));

        for (ExpressDeliverDetail expressDeliverDetail : expressDeliver.getDetails()) {
            //设置请求头
            MessageReq req = new MessageReq();
            HeadMessageReq head = new HeadMessageReq();
            head.setTransType(ErpConstant.sf_action_code_order);
            head.setTransMessageId(expressDeliverDetail.getExpressNo().replace(ErpConstant.no_expressDelivery_perfix, ""));
            req.setHead(head);

            OrderReqDto orderReqDto = new OrderReqDto();
            orderReqDto.setOrderId(expressDeliverDetail.getExpressNo());
            orderReqDto.setExpressType((short) 1);
            orderReqDto.setPayMethod((short) 1);
            orderReqDto.setNeedReturnTrackingNo((short) 0);
            orderReqDto.setIsDoCall((short) 1);
            orderReqDto.setIsGenBillNo((short) 1);
            orderReqDto.setCustId(sfExpress.getCustId());
            /**
             * 月结卡号对应的网点，如果付款方式为第三方月结卡号支付(即payMethod=3)，则必填
             */
//            orderReqDto.setPayArea(sfExpress.getPayArea());
            orderReqDto.setSendStartTime(dateUtil.getSimpleDateFormat().format(expressDeliver.getDate()));
            orderReqDto.setRemark("易碎物品，小心轻放");

            //收件人信息
            DeliverConsigneeInfoDto consigneeInfoDto = new DeliverConsigneeInfoDto();
            consigneeInfoDto.setCompany(expressDeliver.getReceiverCompany());
            consigneeInfoDto.setAddress(expressDeliver.getReceiverAddress());
            consigneeInfoDto.setCity(expressDeliver.getReceiverCity());
            consigneeInfoDto.setProvince(expressDeliver.getReceiverProvince());
            consigneeInfoDto.setCountry(expressDeliver.getReceiverCountry());
            consigneeInfoDto.setShipperCode(expressDeliver.getReceiverPostCode());
            consigneeInfoDto.setMobile(expressDeliver.getReceiverMobile());
            consigneeInfoDto.setTel(expressDeliver.getReceiverTel());
            consigneeInfoDto.setContact(expressDeliver.getReceiver());

            //寄件人信息
            DeliverConsigneeInfoDto deliverInfoDto = new DeliverConsigneeInfoDto();
            deliverInfoDto.setCompany(expressDeliver.getSenderCompany());
            deliverInfoDto.setAddress(expressDeliver.getSenderAddress());
            deliverInfoDto.setCity(expressDeliver.getSenderCity());
            deliverInfoDto.setProvince(expressDeliver.getSenderProvince());
            deliverInfoDto.setCountry(expressDeliver.getSenderCountry());
            deliverInfoDto.setShipperCode(expressDeliver.getSenderPostCode());
            deliverInfoDto.setMobile(expressDeliver.getSenderMobile());
            deliverInfoDto.setTel(expressDeliver.getSenderTel());
            deliverInfoDto.setContact(expressDeliver.getSender());

            //货物信息
            CargoInfoDto cargoInfoDto = new CargoInfoDto();
            cargoInfoDto.setParcelQuantity(Integer.valueOf(1));
            cargoInfoDto.setCargo(expressDeliverDetail.getProductNo());
            cargoInfoDto.setCargoCount(Integer.toString((int)Math.rint(expressDeliverDetail.getQuantity().doubleValue())));
            cargoInfoDto.setCargoUnit(expressDeliverDetail.getUnit());
            cargoInfoDto.setCargoAmount(Integer.toString((int)Math.rint(expressDeliverDetail.getPrice().doubleValue())));

            orderReqDto.setDeliverInfo(deliverInfoDto);
            orderReqDto.setConsigneeInfo(consigneeInfoDto);
            orderReqDto.setCargoInfo(cargoInfoDto);
            req.setBody(orderReqDto);

            System.out.println("传入参数" + ToStringBuilder.reflectionToString(req));
            MessageResp<OrderRespDto> messageResp = null;
            try {
                messageResp = OrderTools.order(url, appInfo, req);
            } catch (Exception e) {
                e.printStackTrace();
                result += CommonConstant.fail;
            }

            if (messageResp.getHead().getCode().equals(ErpConstant.sf_response_code_success) &&
                    messageResp.getBody().getRemarkCode().equals("01")) {
                result += CommonConstant.success;
            } else {
                result += CommonConstant.fail;
                logger.info(messageResp.getHead().getMessage());
            }
        }

        logger.info("sfExpressOrder end, " + result);
        return result.equals(CommonConstant.fail) ? result : result.substring(CommonConstant.fail.length());
    }

    public List<OrderQueryRespDto> sfExpressOrderQuery(ExpressDeliver expressDeliver) {
        logger.info("sfExpressOrderQuery start");

        List<OrderQueryRespDto> orderQueryRespDtos = new ArrayList<>();
        for (ExpressDeliverDetail detail : expressDeliver.getDetails()) {
            OrderQueryRespDto orderQueryRespDto = sfExpressOrderQuery(detail.getExpressNo());

            if (orderQueryRespDto != null) {
                orderQueryRespDtos.add(orderQueryRespDto);
            }
        }

        logger.info("sfExpressOrderQuery end");
        return  orderQueryRespDtos;
    }

    /**
     * sf快递单订单查询
     * @param expressNo
     * @return
     */
    public OrderQueryRespDto sfExpressOrderQuery(String expressNo) {
        String url = httpProxyDiscovery.getHttpProxyAddress() + sfExpress.getOrderQueryUri();
        AppInfo appInfo = new AppInfo();
        appInfo.setAppId(sfExpress.getAppId());
        appInfo.setAppKey(sfExpress.getAppKey());
        appInfo.setAccessToken(getSfToken(sfExpress.getAppId(), sfExpress.getAppKey()));

        MessageReq req = new MessageReq();
        HeadMessageReq head = new HeadMessageReq();
        head.setTransType(ErpConstant.sf_action_code_query_order);
        head.setTransMessageId(erpDao.getSfTransMessageId());
        req.setHead(head);

        OrderQueryReqDto oderQueryReqDto = new OrderQueryReqDto();
        oderQueryReqDto.setOrderId(expressNo);
        req.setBody(oderQueryReqDto);

        MessageResp<OrderQueryRespDto> messageResp = null;
        try {
            messageResp = OrderTools.orderQuery(url, appInfo, req);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (messageResp.getHead().getCode().equals(ErpConstant.sf_response_code_success)) {
            return messageResp.getBody();
        } else {
            return null;
        }
    }

    /**
     * 下载出库商品快递单
     * @param expressDeliver
     * @return
     */
    public String downloadSfWaybill(ExpressDeliver expressDeliver) {
        logger.info("downloadSfWaybill start:" + expressDeliver.getId());
        String sfWaybills = "";

        List<OrderQueryRespDto> orderQueryRespDtos = sfExpressOrderQuery(expressDeliver);
        if (!orderQueryRespDtos.isEmpty()){
            for (OrderQueryRespDto orderQueryRespDto : orderQueryRespDtos) {

                if (orderQueryRespDto.getMailNo() != null) {
                    String sfWaybill = downloadSfWaybill(orderQueryRespDto.getOrderId());
                    if (sfWaybill.contains("<img")) {
                        sfWaybills += sfWaybill;

                        ExpressDeliverDetail expressDeliverDetail = new ExpressDeliverDetail();
                        expressDeliverDetail.setExpressNo(orderQueryRespDto.getOrderId());
                        ExpressDeliverDetail dbExpressDeliverDetail = (ExpressDeliverDetail) erpDao.query(expressDeliverDetail).get(0);
                        expressDeliverDetail.setState(ErpConstant.express_detail_state_sended);
                        expressDeliverDetail.setId(dbExpressDeliverDetail.getId());
                        erpDao.updateById(expressDeliverDetail.getId(), expressDeliverDetail);
                    }
                }
            }
        }

        logger.info("downloadSfWaybill end");
        return sfWaybills;
    }

    /**
     * 下载顺丰快递单
     * @param expressNo
     * @return
     */
    public String downloadSfWaybill(String expressNo) {
        String sfWaybillImage = "";

        String url = httpProxyDiscovery.getHttpProxyAddress() + sfExpress.getImageUri();
        AppInfo appInfo = new AppInfo();
        appInfo.setAppId(sfExpress.getAppId());
        appInfo.setAppKey(sfExpress.getAppKey());
        appInfo.setAccessToken(getSfToken(sfExpress.getAppId(), sfExpress.getAppKey()));

        //设置请求头
        MessageReq<WaybillReqDto> req = new MessageReq<>();
        HeadMessageReq head = new HeadMessageReq();
        head.setTransType(ErpConstant.sf_action_code_download_waybill);
        head.setTransMessageId(erpDao.getSfTransMessageId());
        req.setHead(head);

        WaybillReqDto waybillReqDto = new WaybillReqDto();
        waybillReqDto.setOrderId(expressNo);
        req.setBody(waybillReqDto);

        try {
            MessageResp<WaybillRespDto> messageResp = WaybillDownloadTools.waybillDownload(url, appInfo, req);

            if (messageResp.getHead().getCode().equals(ErpConstant.sf_response_code_success)) {
                String[] images = messageResp.getBody().getImages();

                if (images != null) {
                    for (String image : images) {
                        sfWaybillImage += "<img src='data:image/png;base64," + image + "'/><br/><br/>";
                    }
                }
            }
        } catch (Exception e){
            e.printStackTrace();
        }

        return sfWaybillImage;
    }

    /**
     * 获取顺丰 token
     * @param appId
     * @param appKey
     * @return
     */
    public String getSfToken(String appId, String appKey) {
        logger.info("getSfToken start");
        String token = (String) erpDao.getFromRedis(ErpConstant.sf_access_token_key);

        if (token == null) {
            setSfTokens();
        }

        logger.info("getSfToken end");
        return (String) erpDao.getFromRedis(ErpConstant.sf_access_token_key);
    }

    public void setSfTokens() {
        logger.info("setSfTokens start");

        String url = httpProxyDiscovery.getHttpProxyAddress() + sfExpress.getTokenUri();
        AppInfo appInfo = new AppInfo();
        appInfo.setAppId(sfExpress.getAppId());
        appInfo.setAppKey(sfExpress.getAppKey());

        MessageReq<TokenReqDto> req = new MessageReq<>();
        HeadMessageReq head = new HeadMessageReq();
        head.setTransType(ErpConstant.sf_action_code_access_token);
        head.setTransMessageId(erpDao.getSfTransMessageId());
        req.setHead(head);

        try {
            MessageResp<TokenRespDto> messageResp = SecurityTools.applyAccessToken(url, appInfo, req);

            if (messageResp.getHead().getCode().equals(ErpConstant.sf_response_code_success)) {
                erpDao.storeToRedis(ErpConstant.sf_access_token_key, messageResp.getBody().getAccessToken(), ErpConstant.sf_token_time);
                erpDao.storeToRedis(ErpConstant.sf_refresh_token_key, messageResp.getBody().getRefreshToken(), ErpConstant.sf_refresh_token_time);
            }

            logger.info(messageResp.getHead().getCode() + "," + messageResp.getHead().getMessage() + "," +
                    messageResp.getBody().getAccessToken() + "," + messageResp.getBody().getRefreshToken());

        } catch (Exception e) {
            e.printStackTrace();
        }

        logger.info("setSfTokens end");
    }

    public void refreshSfTokens(String accessToken, String refreshToken) {
        logger.info("refreshSfTokens start:" + accessToken + "," + refreshToken);

        String url = httpProxyDiscovery.getHttpProxyAddress() + sfExpress.getTokenRefreshUri();
        AppInfo appInfo = new AppInfo();
        appInfo.setAppId(sfExpress.getAppId());
        appInfo.setAppKey(sfExpress.getAppKey());
        appInfo.setAccessToken(accessToken);
        appInfo.setRefreshToken(refreshToken);

        MessageReq req = new MessageReq();
        HeadMessageReq head = new HeadMessageReq();
        head.setTransType(ErpConstant.sf_action_code_refresh_Token);
        head.setTransMessageId(erpDao.getSfTransMessageId());
        req.setHead(head);

        try {
            MessageResp<TokenRespDto> messageResp = SecurityTools.refreshAccessToken(url, appInfo, req);

            if (messageResp.getHead().getCode().equals(ErpConstant.sf_response_code_refresh_token_unExist) ||
                    messageResp.getHead().getCode().equals(ErpConstant.sf_response_code_refresh_token_timeout)){
                setSfTokens();

            } else if(messageResp.getHead().getCode().equals(ErpConstant.sf_response_code_success)) {
                erpDao.storeToRedis(ErpConstant.sf_access_token_key, messageResp.getBody().getAccessToken(), ErpConstant.sf_token_time);
                erpDao.storeToRedis(ErpConstant.sf_refresh_token_key, messageResp.getBody().getRefreshToken(), ErpConstant.sf_refresh_token_time);
            }

            logger.info(messageResp.getHead().getCode() + "," + messageResp.getHead().getMessage() + "," +
                    messageResp.getBody().getAccessToken() + "," + messageResp.getBody().getRefreshToken());

        } catch (Exception e) {
            e.printStackTrace();
        }

        logger.info("refreshSfTokens end");
    }

    public ExpressDeliver getSfExpressOrderByStockInOut(StockInOut stockInOut) {
        logger.info("getSfExpressOrderByStockInOut start:" + stockInOut.getId());
        ExpressDeliver expressDeliver = null;

        for (StockInOutDetail detail : stockInOut.getDetails()) {
            StockInOutDetail dbDetail = (StockInOutDetail) erpDao.queryById(detail.getId(), detail.getClass());

            StockInOutDetailProduct detailProduct = (StockInOutDetailProduct) dbDetail.getStockInOutDetailProducts().toArray()[0];
            Product dbProduct = (Product) erpDao.queryById(detailProduct.getProduct().getId(), detailProduct.getProduct().getClass());
            ExpressDeliverDetail dbExpressDeliverDetail = queryLastExpressDeliverDetailByProduct(dbProduct);

            if (dbExpressDeliverDetail != null) {
                if (expressDeliver == null) {
                    expressDeliver = (ExpressDeliver) erpDao.queryById(dbExpressDeliverDetail.getExpressDeliver().getId(), dbExpressDeliverDetail.getExpressDeliver().getClass());
                    expressDeliver.setDetails(new HashSet<>());
                }
                expressDeliver.getDetails().add(dbExpressDeliverDetail);
            }
        }

        logger.info("getSfExpressOrderByStockInOut end");
        return expressDeliver;
    }

    public ExpressDeliverDetail queryLastExpressDeliverDetailByProduct(Product product) {
        ExpressDeliverDetailProduct detailProduct = new ExpressDeliverDetailProduct();
        detailProduct.setProduct(product);

        List<ExpressDeliverDetailProduct> detailProducts = erpDao.query(detailProduct);
        return detailProducts.isEmpty() ? null : detailProducts.get(0).getExpressDeliverDetail();
    }
}