package datapath

import (
	"encoding/json"
	"sort"
	"sync"
)

type clientTraffic struct {
	IPAddress     string `json:"ipAddress"`
	UploadBytes   int64  `json:"uploadBytes"`
	DownloadBytes int64  `json:"downloadBytes"`
}

type clientCounters struct {
	mu      sync.RWMutex
	clients map[string]*clientTraffic
}

func newClientCounters() *clientCounters {
	return &clientCounters{clients: make(map[string]*clientTraffic)}
}

func (c *clientCounters) add(ip string, upload bool, count int64) {
	if c == nil || ip == "" || count <= 0 { return }
	c.mu.Lock()
	defer c.mu.Unlock()
	row := c.clients[ip]
	if row == nil {
		row = &clientTraffic{IPAddress: ip}
		c.clients[ip] = row
	}
	if upload { row.UploadBytes += count } else { row.DownloadBytes += count }
}

func (c *clientCounters) statsJSON() string {
	c.mu.RLock()
	rows := make([]clientTraffic, 0, len(c.clients))
	for _, row := range c.clients { rows = append(rows, *row) }
	c.mu.RUnlock()
	sort.Slice(rows, func(i, j int) bool { return rows[i].IPAddress < rows[j].IPAddress })
	payload, err := json.Marshal(struct { Clients []clientTraffic `json:"clients"` }{Clients: rows})
	if err != nil { return `{"clients":[]}` }
	return string(payload)
}
