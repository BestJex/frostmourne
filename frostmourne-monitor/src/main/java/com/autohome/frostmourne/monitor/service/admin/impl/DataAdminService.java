package com.autohome.frostmourne.monitor.service.admin.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Resource;

import com.autohome.frostmourne.core.contract.PagerContract;
import com.autohome.frostmourne.core.contract.ProtocolException;
import com.autohome.frostmourne.core.jackson.JacksonUtil;
import com.autohome.frostmourne.monitor.contract.DataNameContract;
import com.autohome.frostmourne.monitor.contract.DataOption;
import com.autohome.frostmourne.monitor.contract.DataSourceContract;
import com.autohome.frostmourne.monitor.contract.DataSourceOption;
import com.autohome.frostmourne.monitor.dao.elasticsearch.ElasticsearchInfo;
import com.autohome.frostmourne.monitor.dao.elasticsearch.ElasticsearchSourceManager;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.domain.DataName;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.domain.DataSource;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.repository.IDataNameRepository;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.repository.IDataSourceRepository;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.repository.IMetricRepository;
import com.autohome.frostmourne.monitor.service.admin.IDataAdminService;
import com.autohome.frostmourne.monitor.transform.DataNameTransformer;
import com.autohome.frostmourne.monitor.transform.DataSourceTransformer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.stereotype.Service;

@Service
public class DataAdminService implements IDataAdminService {

    @Resource
    private IDataSourceRepository dataSourceRepository;

    @Resource
    private IDataNameRepository dataNameRepository;

    @Resource
    private IMetricRepository metricRepository;

    @Resource
    private ElasticsearchSourceManager elasticsearchSourceManager;

    public DataSourceContract findDatasourceById(Long id) {
        Optional<DataSource> optionalDataSource = dataSourceRepository.selectByPrimaryKey(id);
        return optionalDataSource.map(DataSourceTransformer::model2Contract).orElse(null);
    }

    @Override
    public boolean saveDataSource(String account, DataSourceContract dataSourceContract) {
        DataSource dataSource = new DataSource();
        dataSource.setDatasource_name(dataSourceContract.getDatasource_name());
        dataSource.setDatasource_type(dataSourceContract.getDatasource_type());
        dataSource.setModifier(account);
        dataSource.setService_address(dataSourceContract.getService_address());
        dataSource.setModify_at(new Date());
        if (dataSourceContract.getSettings() != null && dataSourceContract.getSettings().size() > 0) {
            dataSource.setProperties(JacksonUtil.serialize(dataSourceContract.getSettings()));
        }
        if (dataSourceContract.getId() != null && dataSourceContract.getId() > 0) {
            dataSource.setId(dataSourceContract.getId());
            if (dataSource.getDatasource_type().equalsIgnoreCase("elasticsearch")) {
                boolean reloadResult = elasticsearchSourceManager.reloadEsRestClientContainer(new ElasticsearchInfo(dataSourceContract));
                if (!reloadResult) {
                    return false;
                }
            }
            return dataSourceRepository.updateByPrimaryKeySelective(dataSource) > 0;
        }
        dataSource.setCreator(account);
        dataSource.setCreate_at(new Date());
        return dataSourceRepository.insert(dataSource) > 0;

    }

    @Override
    public boolean removeDataSource(Long id) {
        long datasourceCount = metricRepository.datasourceCount(id);
        if (datasourceCount > 0) {
            throw new ProtocolException(600, "数据源正在使用无法删除");
        }
        return this.dataSourceRepository.deleteByPrimaryKey(id) > 0;
    }

    @Override
    public PagerContract<DataSourceContract> findDatasource(int pageIndex, int pageSize, String datasourceType) {
        Page page = PageHelper.startPage(pageIndex, pageSize);
        List<DataSource> list = this.dataSourceRepository.find(datasourceType);
        return new PagerContract<>(list.stream().map(DataSourceTransformer::model2Contract).collect(Collectors.toList()),
                page.getPageSize(), page.getPageNum(), (int) page.getTotal());
    }

    @Override
    public List<DataSource> findDataSourceByType(String datasourceType) {
        return this.dataSourceRepository.find(datasourceType);
    }

    @Override
    public List<DataOption> dataOptions() {
        List<DataSource> dataSourceList = this.dataSourceRepository.find(null);
        List<DataName> dataNameList = this.dataNameRepository.find(null, null);
        Map<String, List<DataSourceOption>> dataOptionMap = new HashMap<>();
        for (DataSource dataSource : dataSourceList) {
            DataSourceOption dataSourceOption = new DataSourceOption();
            dataSourceOption.setDataSource(dataSource);
            dataSourceOption.setDataNameContractList(dataNameList.stream()
                    .filter(dataName -> dataName.getData_source_id().equals(dataSource.getId()))
                    .map(DataAdminService::toDataNameContract)
                    .collect(Collectors.toList()));
            if (dataOptionMap.containsKey(dataSource.getDatasource_type())) {
                dataOptionMap.get(dataSource.getDatasource_type()).add(dataSourceOption);
            } else {
                List<DataSourceOption> dataSourceOptionList = new ArrayList<>();
                dataSourceOptionList.add(dataSourceOption);
                dataOptionMap.put(dataSource.getDatasource_type(), dataSourceOptionList);
            }
        }

        List<DataOption> dataOptionList = new ArrayList<>();
        for (Map.Entry<String, List<DataSourceOption>> entry : dataOptionMap.entrySet()) {
            DataOption dataOption = new DataOption();
            dataOption.setDatasourceType(entry.getKey());
            dataOption.setDataSourceOptionList(entry.getValue());
            dataOptionList.add(dataOption);
        }

        return dataOptionList;
    }

    @Override
    public boolean saveDataName(String account, DataNameContract dataNameContract) {
        DataName dataName = new DataName();
        Date now = new Date();
        dataName.setData_name(dataNameContract.getData_name());
        dataName.setModifier(account);
        dataName.setModify_at(now);
        dataName.setData_source_id(dataNameContract.getData_source_id());
        dataName.setDatasource_type(dataNameContract.getDatasource_type());
        dataName.setDisplay_name(dataNameContract.getDisplay_name());
        dataName.setProperties(JacksonUtil.serialize(dataNameContract.getSettings()));
        dataName.setTimestamp_field(dataNameContract.getTimestamp_field());
        if (dataNameContract.getId() != null && dataNameContract.getId() > 0) {
            dataName.setId(dataNameContract.getId());
            return this.dataNameRepository.updateByPrimaryKeySelective(dataName) > 0;
        }
        Optional<DataName> oldDataName = this.dataNameRepository.findByName(dataNameContract.getData_name());
        if (oldDataName.isPresent()) {
            throw new ProtocolException(504, "数据名称发生重复");
        }
        dataName.setCreator(account);
        dataName.setCreate_at(now);
        return this.dataNameRepository.insert(dataName) > 0;
    }

    @Override
    public boolean removeDataName(Long datanameId) {
        long datanameCount = this.metricRepository.datanameCount(datanameId);
        if (datanameCount > 0) {
            throw new ProtocolException(600, "数据名正在使用无法删除");
        }
        return this.dataNameRepository.deleteByPrimaryKey(datanameId) > 0;
    }

    @Override
    public PagerContract<DataNameContract> findDataName(int pageIndex, int pageSize, String datasourceType, Long datasourceId) {
        Page page = PageHelper.startPage(pageIndex, pageSize);
        List<DataName> list = this.dataNameRepository.find(datasourceType, datasourceId);
        return new PagerContract<>(list.stream().map(DataAdminService::toDataNameContract).collect(Collectors.toList()),
                page.getPageSize(), page.getPageNum(), (int) page.getTotal());
    }

    @Override
    public List<DataNameContract> findDataNameByType(String datasourceType) {
        List<DataName> list = this.dataNameRepository.find(datasourceType, null);
        return list.stream().map(DataAdminService::toDataNameContract).collect(Collectors.toList());
    }

    @Override
    public DataNameContract findDataNameByName(String name) {
        Optional<DataName> optionalDataName = dataNameRepository.findByName(name);
        return optionalDataName.map(DataNameTransformer::model2Contract).orElse(null);
    }

    static DataNameContract toDataNameContract(DataName dataName) {
        DataNameContract dataNameContract = new DataNameContract();
        dataNameContract.setId(dataName.getId());
        dataNameContract.setData_source_id(dataName.getData_source_id());
        dataNameContract.setDatasource_type(dataName.getDatasource_type());
        dataNameContract.setData_name(dataName.getData_name());
        dataNameContract.setDisplay_name(dataName.getDisplay_name());
        dataNameContract.setTimestamp_field(dataName.getTimestamp_field());
        dataNameContract.setCreator(dataName.getCreator());
        dataNameContract.setCreate_at(dataName.getCreate_at());
        dataNameContract.setModifier(dataName.getModifier());
        dataNameContract.setModify_at(dataName.getModify_at());
        dataNameContract.setSettings(JacksonUtil.deSerialize(dataName.getProperties(), new TypeReference<Map<String, String>>() {
        }));

        return dataNameContract;
    }
}
